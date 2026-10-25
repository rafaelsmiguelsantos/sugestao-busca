package dev.sugestao.busca.dominio.agregado;

import java.time.Duration;
import java.time.Instant;

/**
 * Como clique e adesão viram afinidade: com meia-vida e com piso de confiança.
 *
 * <p>Substitui duas decisões que pareciam boas isoladas e, juntas, faziam o
 * sistema acreditar rápido demais e nunca esquecer.
 *
 * <p><strong>O problema do piso.</strong> A afinidade era normalizada pela
 * jornada mais forte <em>da própria pessoa</em>. A intenção estava certa — não
 * transformar a medida num segundo índice de popularidade, que já existe no
 * {@code ctr} — mas o efeito era que <em>um</em> clique dava afinidade 1,0:
 * {@code {credito: 2.0}} dividido pelo próprio máximo, 2,0, é 1,0. Um clique
 * acidental punha a pessoa no teto de promoção da jornada.
 *
 * <p>Agora o divisor é constante. O máximo da pessoa some da conta, mas a
 * propriedade que ele protegia fica: quem clicou três vezes e quem clicou
 * trezentas chegam no mesmo 1,0, porque o valor é limitado em 1 — e nenhum dos
 * dois é comparado com a população.
 *
 * <p><strong>O problema do esquecimento.</strong> A afinidade era substituída a
 * cada rodada, o que apagava tudo quando a janela não tinha cliques, e depois
 * passou a ser preservada, o que nunca esquecia. A terceira forma é a certa:
 * cada evento vale menos conforme envelhece.
 *
 * <p>E o decaimento é por <strong>idade do evento</strong>, nunca por rodada.
 * Decaimento por rodada é o que parece natural e é uma armadilha: o agendador
 * roda a cada 15 s, então um fator por rodada esvaziaria a afinidade em minutos;
 * e trocar o intervalo do agendador mudaria a velocidade do esquecimento, que é
 * uma decisão de produto virando efeito colateral de configuração de
 * infraestrutura. Por idade, rodar a agregação uma vez ou cem vezes no mesmo
 * minuto dá a mesma resposta.
 */
public record PesosDeAfinidade(Duration janela, Duration meiaVida, double pisoDeConfianca) {

    public PesosDeAfinidade {
        if (janela == null || janela.isZero() || janela.isNegative()) {
            throw new IllegalArgumentException("a janela da afinidade precisa ser positiva");
        }
        if (meiaVida == null || meiaVida.isZero() || meiaVida.isNegative()) {
            throw new IllegalArgumentException("a meia-vida precisa ser positiva");
        }
        if (pisoDeConfianca <= 0) {
            throw new IllegalArgumentException(
                    "piso de confianca zero devolve afinidade maxima para o primeiro clique");
        }
        if (janela.compareTo(meiaVida) < 0) {
            // Janela menor que a meia-vida corta o evento antes de ele ter
            // decaido de forma apreciavel: o esquecimento voltaria a ser um
            // corte seco, que e exatamente o que esta classe existe para
            // substituir.
            throw new IllegalArgumentException(
                    "janela menor que a meia-vida transforma o decaimento de volta num corte seco");
        }
    }

    /**
     * Noventa dias de janela, sete de meia-vida, seis pontos de piso.
     *
     * <p>Com clique valendo 2 e adesão valendo 5, seis pontos são três cliques
     * de hoje, ou uma adesão mais um clique. Um clique sozinho dá 0,33, que no
     * ranking é um impulso de 1,17x — ruído, que é o que um clique é.
     *
     * <p>A janela é longa e a meia-vida é curta de propósito, e os dois números
     * fazem trabalhos diferentes. A meia-vida decide a velocidade do
     * esquecimento: sete dias significam que três cliques de duas semanas atrás
     * valem 0,25 de afinidade, e de um mês atrás valem 0,05. A janela só decide
     * quando parar de somar, e ela é generosa porque interesse em crédito
     * imobiliário não se demonstra todo dia — cortar em 24 h chamaria de
     * desinteresse o que é só intervalo entre acessos.
     */
    public static PesosDeAfinidade padrao() {
        return new PesosDeAfinidade(Duration.ofDays(90), Duration.ofDays(7), 6.0);
    }

    /**
     * O peso de um evento, já descontada a idade.
     *
     * <p>{@code peso × 0.5^(idade / meiaVida)}. Evento de agora vale o peso
     * cheio; de uma meia-vida atrás, metade; de duas, um quarto.
     *
     * <p>Evento com data no futuro vale o peso cheio em vez de valer mais: o
     * relógio do aparelho erra, e um evento adiantado não deve render mais que
     * um evento honesto. A ingestão já defende contra isso, e aqui é cinto e
     * suspensório.
     */
    public double pesoComIdade(double pesoBase, Instant ocorridoEm, Instant agora) {
        if (ocorridoEm == null || !ocorridoEm.isBefore(agora)) {
            return pesoBase;
        }

        double idadeEmMeiasVidas =
                (double) Duration.between(ocorridoEm, agora).toMillis() / meiaVida.toMillis();

        return pesoBase * Math.pow(0.5, idadeEmMeiasVidas);
    }

    /**
     * Pontos acumulados viram afinidade entre 0 e 1.
     *
     * <p>Limitado em 1 porque o fator do ranking é multiplicador com teto, e
     * deixar passar de 1 faria o teto depender de quantos cliques a pessoa deu —
     * o que é a definição de bolha.
     */
    public double afinidadeDe(double pontos) {
        if (pontos <= 0) {
            return 0.0;
        }
        return Math.min(1.0, pontos / pisoDeConfianca);
    }

    public Instant corte(Instant agora) {
        return agora.minus(janela);
    }
}
