package dev.sugestao.busca.aplicacao.casodeuso;

import dev.sugestao.busca.aplicacao.ParametrosDeSugestao;
import dev.sugestao.busca.aplicacao.porta.entrada.ConsultaDeSugestao;
import dev.sugestao.busca.aplicacao.porta.entrada.SugerirSugestoes;
import dev.sugestao.busca.aplicacao.porta.saida.CatalogoDeSugestoes;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.Candidato;
import dev.sugestao.busca.dominio.ClassificadorDeOrigem;
import dev.sugestao.busca.dominio.ListaDeSugestoes;
import dev.sugestao.busca.dominio.Misturador;
import dev.sugestao.busca.dominio.Normalizacao;
import dev.sugestao.busca.dominio.Origem;
import dev.sugestao.busca.dominio.Peneira;
import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.Prefixo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * O caso de uso. Orquestra o leque paralelo, aplica os prazos e entrega a lista.
 *
 * <p>Esta classe não sabe que existe HTTP, nem Solr, nem Spring. Ela fala com
 * duas portas de saída e com quatro objetos de domínio, e é testável inteira sem
 * subir nada.
 *
 * <p>A ordem das etapas é a do desenho e não é intercambiável: classificar o
 * evento precisa do perfil; a peneira precisa da classificação, porque o piso de
 * k-anonimato só vale para o que veio do índice; e a mistura precisa da peneira,
 * senão a cota seria gasta com linhas que iam ser removidas depois.
 */
public class SugerirSugestoesCasoDeUso implements SugerirSugestoes {

    private final CatalogoDeSugestoes catalogo;
    private final RepositorioDePerfil perfis;
    private final ClassificadorDeOrigem classificador;
    private final Peneira peneira;
    private final Misturador misturador;
    private final ParametrosDeSugestao parametros;

    public SugerirSugestoesCasoDeUso(
            CatalogoDeSugestoes catalogo,
            RepositorioDePerfil perfis,
            ClassificadorDeOrigem classificador,
            Peneira peneira,
            Misturador misturador,
            ParametrosDeSugestao parametros) {
        this.catalogo = catalogo;
        this.perfis = perfis;
        this.classificador = classificador;
        this.peneira = peneira;
        this.misturador = misturador;
        this.parametros = parametros;
    }

    @Override
    public ListaDeSugestoes executar(ConsultaDeSugestao consulta) {
        Prefixo prefixo = Prefixo.de(consulta.prefixoDigitado());

        // O corte mais barato do conjunto, e o único que acontece antes de
        // qualquer rede. Com um caractere, o leque de candidatos é o índice
        // inteiro e o prazo de 18 ms não se sustenta.
        if (prefixo.curtoDemais(parametros.prefixoMinimo())) {
            return ListaDeSugestoes.vazia();
        }

        var cliente = consulta.cliente();
        long inicio = System.nanoTime();

        // O executor fecha esperando as tarefas. Quem garante que isso não
        // transforma o prazo em ficção são duas coisas juntas: o cancelamento
        // com interrupção em `aguardar`, e o timeout de requisição configurado
        // no próprio cliente HTTP do adaptador. Sem o segundo, uma dependência
        // surda à interrupção seguraria a resposta depois do prazo vencido.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // O segmento vem do token: a consulta ao índice arranca junto com a
            // leitura do perfil, não atrás dela.
            Future<List<Candidato>> doIndice = executor.submit(() ->
                    catalogo.candidatos(prefixo, cliente.segmento(), parametros.candidatosDoIndice()));

            Future<Perfil> doPerfil = executor.submit(() ->
                    perfis.porCliente(cliente.id()));

            List<Candidato> candidatosDoIndice =
                    aguardar(doIndice, inicio, parametros.prazos().solr(), List::of);

            Perfil perfil =
                    aguardar(doPerfil, inicio, parametros.prazos().perfil(), () -> Perfil.NEUTRO);

            return montar(prefixo, candidatosDoIndice, perfil);
        }
    }

    private ListaDeSugestoes montar(Prefixo prefixo, List<Candidato> doIndice, Perfil perfil) {
        var candidatos = new ArrayList<>(
                classificador.classificar(doIndice, perfil, parametros.cotas().evento()));
        candidatos.addAll(candidatosDoHistorico(perfil, prefixo));

        var sobreviventes = peneira.aplicar(candidatos, perfil);

        // Degradada quando o perfil não chegou: a lista ainda é útil, mas é a
        // popular do segmento. O app usa isto para não registrar a impressão
        // como se fosse personalizada — senão a camada 4 aprenderia com ruído.
        boolean degradada = !perfil.conhecido();

        return misturador.misturar(sobreviventes, parametros.cotas(), degradada);
    }

    /**
     * O histórico não passa pelo índice. Ele já está no perfil, custa zero na
     * requisição, e por não sair do dono não tem como vazar para ninguém.
     *
     * <p>O score cai com a posição porque a lista do perfil chega da busca mais
     * recente para a mais antiga. O valor não precisa ser exato: a cota de duas
     * linhas é que limita o histórico, não o score.
     */
    private List<Candidato> candidatosDoHistorico(Perfil perfil, Prefixo prefixo) {
        var casam = perfil.historico().stream()
                .filter(termo -> Normalizacao.aplicar(termo).startsWith(prefixo.valor()))
                .limit(parametros.cotas().historico() * 4L)
                .toList();

        var candidatos = new ArrayList<Candidato>(casam.size());
        for (int i = 0; i < casam.size(); i++) {
            String termo = casam.get(i);
            candidatos.add(new Candidato(
                    termo,
                    "",
                    "/busca?q=" + termo.replace(' ', '+'),
                    "busca",
                    "",
                    false,
                    List.of(),
                    0,
                    Math.max(0.5, 1.0 - (i * 0.05)),
                    Origem.HISTORICO));
        }
        return candidatos;
    }

    /**
     * Espera até o prazo da dependência ou até o teto do leque, o que vencer
     * primeiro, e nunca repete a chamada.
     *
     * <p>O prazo é contado do início da requisição, não de quando esta espera
     * começou. Se fosse relativo, a segunda espera ganharia de presente o tempo
     * que a primeira já gastou, e o orçamento de 30 ms viraria 26 sem que
     * ninguém tivesse decidido isso.
     *
     * <p>E o tempo restante é piso zero, não condição de desistência. As duas
     * leituras correm em paralelo, mas são colhidas em sequência: quando a
     * primeira consome o orçamento inteiro, a segunda chega aqui com prazo
     * vencido mesmo tendo respondido em 1 ms. Esperar zero devolve o valor se
     * ele já está pronto e desiste se não está — que é o que o prazo queria
     * dizer. Descartar resultado pronto seria degradar a lista por relógio,
     * sem que nenhuma dependência tivesse falhado.
     */
    private <T> T aguardar(Future<T> futuro, long inicio, Duration prazo, Supplier<T> alternativa) {
        long teto = Math.min(prazo.toNanos(), parametros.prazos().total().toNanos());
        long restante = Math.max(0, teto - (System.nanoTime() - inicio));

        try {
            return futuro.get(restante, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            futuro.cancel(true);
            return alternativa.get();
        } catch (ExecutionException e) {
            // A dependência respondeu com falha. Para o cliente é o mesmo que
            // não ter respondido: a caixa de busca nunca fica sem lista.
            return alternativa.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futuro.cancel(true);
            return alternativa.get();
        }
    }
}
