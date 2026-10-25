package dev.sugestao.busca.aplicacao.casodeuso;

import dev.sugestao.busca.aplicacao.Colheita;
import dev.sugestao.busca.aplicacao.ParametrosDeSugestao;
import dev.sugestao.busca.aplicacao.RastroDaSugestao;
import dev.sugestao.busca.aplicacao.porta.entrada.ConsultaDeSugestao;
import dev.sugestao.busca.aplicacao.porta.entrada.ResultadoDaSugestao;
import dev.sugestao.busca.aplicacao.porta.entrada.SugerirSugestoes;
import dev.sugestao.busca.aplicacao.porta.saida.CatalogoDeSugestoes;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.Candidato;
import dev.sugestao.busca.dominio.ClassificadorDeOrigem;
import dev.sugestao.busca.dominio.Cliente;
import dev.sugestao.busca.dominio.Descarte;
import dev.sugestao.busca.dominio.ListaDeSugestoes;
import dev.sugestao.busca.dominio.Misturador;
import dev.sugestao.busca.dominio.Normalizacao;
import dev.sugestao.busca.dominio.Origem;
import dev.sugestao.busca.dominio.Peneira;
import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.Prefixo;
import dev.sugestao.busca.dominio.Rankeador;
import dev.sugestao.busca.dominio.Restricoes;

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
 * <p>Esta classe não sabe que existe HTTP, nem Solr, nem Spring, nem log. Ela
 * fala com duas portas de saída e com quatro objetos de domínio, e é testável
 * inteira sem subir nada.
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
    private final Rankeador rankeador;
    private final Misturador misturador;
    private final ParametrosDeSugestao parametros;

    public SugerirSugestoesCasoDeUso(
            CatalogoDeSugestoes catalogo,
            RepositorioDePerfil perfis,
            ClassificadorDeOrigem classificador,
            Peneira peneira,
            Rankeador rankeador,
            Misturador misturador,
            ParametrosDeSugestao parametros) {
        this.catalogo = catalogo;
        this.perfis = perfis;
        this.classificador = classificador;
        this.peneira = peneira;
        this.rankeador = rankeador;
        this.misturador = misturador;
        this.parametros = parametros;
    }

    @Override
    public ResultadoDaSugestao executar(ConsultaDeSugestao consulta) {
        long inicio = System.nanoTime();
        Prefixo prefixo = Prefixo.de(consulta.prefixoDigitado());
        var cliente = consulta.cliente();

        // O corte mais barato do conjunto, e o único que acontece antes de
        // qualquer rede. Com um caractere, o leque de candidatos é o índice
        // inteiro e o prazo de 18 ms não se sustenta.
        if (prefixo.curtoDemais(parametros.prefixoMinimo())) {
            return new ResultadoDaSugestao(
                    ListaDeSugestoes.vazia(),
                    rastroDeRecusa(consulta, prefixo, cliente, inicio));
        }

        // O executor fecha esperando as tarefas. Quem garante que isso não
        // transforma o prazo em ficção são duas coisas juntas: o cancelamento
        // com interrupção em `aguardar`, e o timeout de requisição configurado
        // no próprio cliente HTTP do adaptador. Sem o segundo, uma dependência
        // surda à interrupção seguraria a resposta depois do prazo vencido.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // O segmento vem do token: a consulta ao índice arranca junto com a
            // leitura do perfil, não atrás dela.
            Future<List<Candidato>> doIndice = executor.submit(() ->
                    catalogo.candidatos(prefixo, cliente.contexto(), parametros.candidatosDoIndice()));

            Future<Perfil> doPerfil = executor.submit(() ->
                    perfis.porCliente(cliente.id()));

            var indice = aguardar(doIndice, inicio, parametros.prazos().solr(), List::<Candidato>of);
            long msAteIndice = decorridoEmMs(inicio);

            var perfil = aguardar(doPerfil, inicio, parametros.prazos().perfil(), () -> Perfil.NEUTRO);
            long msAtePerfil = decorridoEmMs(inicio);

            return montar(consulta, prefixo, cliente, indice, perfil,
                    inicio, msAteIndice, msAtePerfil);
        }
    }

    private ResultadoDaSugestao montar(
            ConsultaDeSugestao consulta,
            Prefixo prefixo,
            Cliente cliente,
            Colheita<List<Candidato>> indice,
            Colheita<Perfil> colhido,
            long inicio,
            long msAteIndice,
            long msAtePerfil) {

        Perfil perfil = colhido.valor();

        var classificados = classificador.classificar(
                indice.valor(), perfil, parametros.cotas().evento());
        int promovidos = (int) classificados.stream()
                .filter(c -> c.origem() == Origem.EVENTO).count();

        var doHistorico = candidatosDoHistorico(perfil, prefixo);

        var todos = new ArrayList<>(classificados);
        todos.addAll(doHistorico);

        var peneirada = peneira.aplicar(todos, perfil);

        // Ranquear depois de peneirar: não há motivo para pontuar o que já foi
        // removido, e a jornada líder seria eleita contando candidatos que não
        // iam aparecer — uma jornada cheia de ofertas inelegíveis venceria sem
        // ter uma única linha na tela.
        var ranqueamento = rankeador.ranquear(
                peneirada.sobreviventes(), perfil, parametros.pesos());

        var mistura = misturador.misturar(ranqueamento.candidatos(), parametros.cotas());

        var descartes = new ArrayList<Descarte>(peneirada.descartes());
        descartes.addAll(mistura.descartes());

        // Degradada quando o perfil não chegou: a lista ainda é útil, mas é a
        // popular do segmento. O app usa isto para não registrar a impressão
        // como se fosse personalizada — senão a camada 4 aprenderia com ruído.
        boolean degradada = !perfil.conhecido();

        var lista = new ListaDeSugestoes(
                mistura.escolhidos().stream().map(Candidato::paraSugestao).toList(),
                degradada);

        var linhas = new ArrayList<RastroDaSugestao.Linha>(mistura.escolhidos().size());
        for (int i = 0; i < mistura.escolhidos().size(); i++) {
            var c = mistura.escolhidos().get(i);
            linhas.add(new RastroDaSugestao.Linha(i, c.termo(), c.origem(), c.relevancia(), c.destino()));
        }

        var rastro = new RastroDaSugestao(
                consulta.prefixoDigitado(),
                prefixo.valor(),
                cliente.id(),
                cliente.segmento(),
                estadoDoPerfil(colhido),
                msAteIndice,
                msAtePerfil,
                decorridoEmMs(inicio),
                indice.valor().size(),
                doHistorico.size(),
                promovidos,
                ranqueamento.jornadaLider(),
                descartes,
                linhas);

        return new ResultadoDaSugestao(lista, rastro);
    }

    private RastroDaSugestao.EstadoDoPerfil estadoDoPerfil(Colheita<Perfil> colhido) {
        return switch (colhido.motivo()) {
            case RESPONDEU -> colhido.valor().conhecido()
                    ? RastroDaSugestao.EstadoDoPerfil.ENCONTRADO
                    : RastroDaSugestao.EstadoDoPerfil.SEM_PERFIL;
            case PRAZO_VENCIDO, INTERROMPIDA -> RastroDaSugestao.EstadoDoPerfil.PRAZO_VENCIDO;
            case FALHOU -> RastroDaSugestao.EstadoDoPerfil.FALHOU;
        };
    }

    private RastroDaSugestao rastroDeRecusa(
            ConsultaDeSugestao consulta, Prefixo prefixo, Cliente cliente, long inicio) {

        return new RastroDaSugestao(
                consulta.prefixoDigitado(), prefixo.valor(), cliente.id(), cliente.segmento(),
                RastroDaSugestao.EstadoDoPerfil.SEM_PERFIL,
                0, 0, decorridoEmMs(inicio),
                0, 0, 0, "", List.of(), List.of());
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
                    Restricoes.NENHUMA,
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
    private <T> Colheita<T> aguardar(
            Future<T> futuro, long inicio, Duration prazo, Supplier<T> alternativa) {

        long teto = Math.min(prazo.toNanos(), parametros.prazos().total().toNanos());
        long restante = Math.max(0, teto - (System.nanoTime() - inicio));

        try {
            return Colheita.respondeu(futuro.get(restante, TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            futuro.cancel(true);
            return new Colheita<>(alternativa.get(), Colheita.Motivo.PRAZO_VENCIDO);
        } catch (ExecutionException e) {
            // A dependência respondeu com falha. Para o cliente é o mesmo que
            // não ter respondido: a caixa de busca nunca fica sem lista.
            return new Colheita<>(alternativa.get(), Colheita.Motivo.FALHOU);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futuro.cancel(true);
            return new Colheita<>(alternativa.get(), Colheita.Motivo.INTERROMPIDA);
        }
    }

    private static long decorridoEmMs(long inicio) {
        return (System.nanoTime() - inicio) / 1_000_000;
    }
}
