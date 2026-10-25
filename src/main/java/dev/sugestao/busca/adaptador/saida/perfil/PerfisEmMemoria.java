package dev.sugestao.busca.adaptador.saida.perfil;

import dev.sugestao.busca.aplicacao.porta.saida.EscritorDePerfil;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.SituacaoDaConta;
import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O lugar do DynamoDB: lê pelo caminho de 30 ms e escreve pelo caminho de
 * aprendizado, que roda fora da requisição.
 *
 * <p>Implementa as duas portas, mas elas continuam separadas — e é essa
 * separação que o compilador verifica. O caso de uso de sugestão recebe só
 * {@link RepositorioDePerfil} e por isso não tem como escrever, mesmo que
 * alguém quisesse. "Nenhuma seta do caminho de leitura escreve" deixa de ser
 * promessa e vira assinatura de construtor.
 *
 * <p>Começa semeado com os perfis falsos da demonstração e vai sendo atualizado
 * pelo que chega da agregação.
 */
public class PerfisEmMemoria implements RepositorioDePerfil, EscritorDePerfil {

    private final Map<String, Perfil> perfis = new ConcurrentHashMap<>();

    public PerfisEmMemoria(Map<String, Perfil> iniciais) {
        perfis.putAll(iniciais);
    }

    @Override
    public Perfil porCliente(String clienteId) {
        // Cliente sem perfil montado não é erro: é cliente novo. O NEUTRO já
        // diz exatamente isso, e `conhecido = false` deixa a lista marcada
        // como degradada para quem for registrar a impressão.
        return perfis.getOrDefault(clienteId, Perfil.NEUTRO);
    }

    @Override
    public void atualizar(List<ComportamentoDoCliente> comportamentos) {
        for (ComportamentoDoCliente comportamento : comportamentos) {
            perfis.merge(
                    comportamento.clienteId(),
                    doZero(comportamento),
                    (atual, novo) -> mesclar(atual, comportamento));
        }
    }

    /**
     * A agregação de busca só sabe de histórico. Gatilho, elegibilidade,
     * produto contratado e situação da conta vêm de outros produtores que este
     * caminho não enxerga — então ele atualiza o que sabe e preserva o resto.
     *
     * <p>Sobrescrever tudo seria o bug silencioso óbvio aqui: uma busca
     * qualquer apagaria a inadimplência do cliente e o crédito novo voltaria a
     * ser oferecido na tecla seguinte.
     */
    private static Perfil mesclar(Perfil atual, ComportamentoDoCliente novo) {
        return new Perfil(
                // Vazio NÃO sobrescreve, e esta é a correção de um apagamento
                // permanente.
                //
                // A agregação devolve um cliente quando ele teve QUALQUER evento
                // na janela, e monta cada dimensão só do que viu. Um cliente que
                // clicou numa sugestão e não digitou nada em 24 h chegava aqui
                // com histórico vazio — e o vazio era gravado sobre o histórico
                // guardado. Um clique apagava a camada HISTORICO da pessoa, para
                // sempre, e o único sintoma era a lista dela ficar mais pobre.
                //
                // Vazio aqui significa "esta janela não teve nada a dizer sobre
                // esta dimensão", nunca "esta pessoa não tem nada".
                novo.historico().isEmpty() ? atual.historico() : novo.historico(),

                atual.gatilhosAtivos(),
                atual.produtosContratados(),
                atual.elegibilidades(),
                atual.situacaoDaConta(),

                // A afinidade, ao contrário do histórico, SOBRESCREVE — inclusive
                // com vazio. E a diferença entre os dois é a parte interessante.
                //
                // O histórico é acumulado: "o que você digitou ultimamente". Uma
                // janela sem buscas não significa que a pessoa não tem buscas
                // recentes, e por isso vazio não apaga.
                //
                // A afinidade é medida: "em que você demonstrou interesse". Ela
                // tem janela de 90 dias e meia-vida de 7 — o decaimento mora
                // DENTRO do cálculo, por idade de evento. Então o que chega aqui
                // já é a verdade atual, e um mapa vazio significa "nenhum clique
                // em três meses", que é afinidade zero de verdade.
                //
                // Esta linha já foi as duas coisas erradas. Primeiro
                // sobrescrevia com vazio sem ter decaimento, e uma busca sem
                // clique apagava a afinidade inteira. Depois preservava, e nada
                // esfriava nunca. Com o decaimento no lugar certo, sobrescrever
                // volta a ser o certo — porque agora o vazio quer dizer algo.
                novo.afinidadePorJornada(),
                true);
    }

    private static Perfil doZero(ComportamentoDoCliente comportamento) {
        return new Perfil(
                comportamento.historico(),
                comportamento.gatilhos(),
                Set.of(),
                Set.of(),
                // Cliente visto só pela busca não tem situação de conta
                // conhecida, e o lado seguro disso é DESCONHECIDA: a oferta
                // condicionada não aparece até alguém que saiba dizer.
                SituacaoDaConta.DESCONHECIDA,
                comportamento.afinidadePorJornada(),
                true);
    }
}
