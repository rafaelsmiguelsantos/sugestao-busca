package dev.sugestao.busca.adaptador.saida.agregado;

import dev.sugestao.busca.dominio.Segmento;
import dev.sugestao.busca.dominio.evento.EventoDeBusca;
import dev.sugestao.busca.dominio.evento.TipoDeEvento;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliques e adesões falsos, para a demonstração ter afinidade sem ninguém
 * precisar clicar à mão.
 *
 * <p><strong>Por que eventos e não afinidade pronta.</strong> Antes, os perfis
 * de demonstração vinham com {@code Map.of("credito", 1.0)} escrito no código —
 * uma afirmação sobre o resultado, sem nada por trás. Enquanto a escrita de
 * perfil preservava mapa vazio, aquilo sobrevivia. Deixou de sobreviver no
 * momento em que a afinidade passou a ter decaimento e a sobrescrever: a
 * primeira rodada do aprendizado substituiria o número inventado por zero, e a
 * demonstração do README pararia de funcionar sem ninguém entender por quê.
 *
 * <p>Semeando evento, a afinidade da demonstração passa a ser <em>calculada</em>
 * pelo mesmo caminho que calcularia a de um cliente de verdade. O número deixa
 * de ser afirmado e passa a ser derivado, o que é a diferença entre uma
 * demonstração e uma encenação — e de passagem a demonstração prova o pipeline
 * em vez de contorná-lo.
 *
 * <p>Os carimbos de tempo são de minutos atrás, para o decaimento por idade ser
 * desprezível: com meia-vida de sete dias, dez minutos não tiram nada. É a única
 * coisa aqui escolhida por conveniência, e é inofensiva.
 */
public final class EventosDaDemonstracao {

    private EventosDaDemonstracao() {
    }

    /**
     * Com clique valendo 2 pontos, adesão valendo 5 e piso de confiança 6:
     *
     * <pre>
     *   3 cliques          = 6 pontos  -> afinidade 1,00
     *   2 cliques + adesão = 9 pontos  -> afinidade 1,00 (limitado)
     *   1 clique           = 2 pontos  -> afinidade 0,33
     * </pre>
     */
    public static List<EventoDeBusca> iniciais() {
        var eventos = new ArrayList<EventoDeBusca>();

        // Pix recusado há duas horas, e a pessoa vinha mexendo em crédito.
        // Crédito cheio, Pix de passagem.
        cliques(eventos, "pf-pix-recusado", Segmento.PF, "credito", 3);
        cliques(eventos, "pf-pix-recusado", Segmento.PF, "pix", 1);

        // Já contratou CDB: dois cliques e uma adesão explicam por quê.
        cliques(eventos, "pf-investidor", Segmento.PF, "investimento", 2);
        adesao(eventos, "pf-investidor", Segmento.PF, "investimento");

        // Conta negativa. A afinidade dele é com o caminho que resolve, não com
        // o crédito novo que a Peneira veta de qualquer forma.
        cliques(eventos, "pf-negativado", Segmento.PF, "renegociacao", 3);
        cliques(eventos, "pf-negativado", Segmento.PF, "cartao", 1);

        // Uniclass: mesma afinidade do investidor, outra faixa. É o par que
        // mostra o corte de relacionamento acontecendo no índice, antes do
        // perfil.
        cliques(eventos, "pf-uniclass", Segmento.PF, "investimento", 3);

        // pf-novo, pf-sem-elegibilidade, pf-bloqueado e pj-lojista ficam SEM
        // afinidade, de propósito: eles são a linha de base. Sem um perfil sem
        // afinidade nenhuma não há com o que comparar os que têm.
        return List.copyOf(eventos);
    }

    private static void cliques(
            List<EventoDeBusca> destino, String cliente, Segmento segmento,
            String jornada, int quantos) {

        for (int i = 0; i < quantos; i++) {
            destino.add(evento(TipoDeEvento.SUGESTAO_CLICADA, cliente, segmento, jornada, i));
        }
    }

    private static void adesao(
            List<EventoDeBusca> destino, String cliente, Segmento segmento, String jornada) {

        destino.add(evento(TipoDeEvento.ADESAO, cliente, segmento, jornada, 0));
    }

    private static EventoDeBusca evento(
            TipoDeEvento tipo, String cliente, Segmento segmento, String jornada, int ordem) {

        return new EventoDeBusca(
                tipo,
                cliente,
                segmento,
                // Termo vazio: o que importa aqui é a jornada, que é o que a
                // afinidade conta. O termo entraria no histórico, e o histórico
                // destes perfis já é semeado em PerfisDaDemonstracao.
                "",
                List.of(),
                0,
                "",
                jornada,
                false,
                Instant.now().minus(10L + ordem, ChronoUnit.MINUTES));
    }
}
