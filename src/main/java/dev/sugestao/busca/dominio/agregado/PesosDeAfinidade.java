package dev.sugestao.busca.dominio.agregado;

import java.time.Duration;
import java.time.Instant;

/**
 * Como clique e adesão viram afinidade: com meia-vida, com piso de confiança e —
 * desde esta rodada — em dois níveis e com tendência.
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
 *
 * <h2>Os dois níveis (jornada e produto)</h2>
 *
 * <p>A afinidade era só por jornada, e isso a tornava incapaz de responder à
 * pergunta mais comum: <em>financiamento</em>, <em>empréstimo</em> e
 * <em>consignado</em> são todos da jornada {@code credito}, então clicar em
 * consignado promovia empréstimo exatamente igual. O sistema sabia que a pessoa
 * queria crédito e não tinha como saber <em>qual</em>.
 *
 * <p>Agora há duas contas, com o mesmo decaimento e pisos diferentes. O piso do
 * produto é mais baixo que o da jornada de propósito, e isto é o ponto delicado
 * desta classe: <strong>evidência por produto é muito mais escassa que por
 * jornada</strong>. Há meia dúzia de jornadas e vinte e tantos produtos; o
 * clique se espalha, e um piso igual ao da jornada faria a afinidade por produto
 * ficar matematicamente correta e praticamente sempre zero — um recurso inerte
 * que ninguém notaria estar inerte. Baixar o piso é o conserto, e tem um preço
 * declarado: o nível do produto acredita com menos evidência que o da jornada.
 * O teto menor no ranking é o que paga esse preço.
 *
 * <h2>A tendência (janela curta contra janela longa)</h2>
 *
 * <p>Uma exponencial só diz "quanto", não "para onde". Meia-vida de sete dias já
 * faz evento recente dominar, mas não distingue <em>disparou esta semana</em> de
 * <em>constante há um mês</em> — e essa é a diferença entre necessidade
 * transitória e interesse durável, que merecem ofertas diferentes.
 *
 * <p>A tendência é a razão entre os pontos da janela curta e os da longa. Como a
 * curta está contida na longa, a razão vive em [0,1], e o ponto neutro não é 0,5:
 * é {@code curta/longa}, ou seja, 7/30 ≈ 0,23 para os valores padrão. Acima
 * disso, a atividade está concentrada no presente; abaixo, está esfriando.
 *
 * <p><strong>E o piso da tendência existe porque razão de números pequenos é
 * ruído.</strong> Com três cliques no total, "tendência de 7 contra 30 dias" é
 * ficção aritmética: um clique a mais ou a menos move a razão de 0,33 para 0,66.
 * Abaixo de {@code pisoDaTendencia} pontos na janela longa, a tendência devolve
 * neutro — e o fato de ela devolver neutro é observável pela medição, que é como
 * se descobre se este recurso está dizendo algo ou está desligado de fato.
 */
public record PesosDeAfinidade(
        Duration janela,
        Duration meiaVida,
        double pisoDeConfianca,

        /** Piso de confiança do nível do produto. Mais baixo: a evidência é mais escassa. */
        double pisoDeConfiancaDoProduto,

        /** A janela recente da tendência. */
        Duration janelaCurta,

        /** A janela de comparação da tendência. */
        Duration janelaLonga,

        /** Pontos mínimos na janela longa para a tendência ser calculada. */
        double pisoDaTendencia) {

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
        if (pisoDeConfiancaDoProduto <= 0) {
            throw new IllegalArgumentException(
                    "piso de confianca do produto zero devolve afinidade maxima para o primeiro clique");
        }
        if (janela.compareTo(meiaVida) < 0) {
            // Janela menor que a meia-vida corta o evento antes de ele ter
            // decaido de forma apreciavel: o esquecimento voltaria a ser um
            // corte seco, que e exatamente o que esta classe existe para
            // substituir.
            throw new IllegalArgumentException(
                    "janela menor que a meia-vida transforma o decaimento de volta num corte seco");
        }
        if (janelaCurta == null || janelaLonga == null
                || janelaCurta.isZero() || janelaLonga.isZero()
                || janelaCurta.isNegative() || janelaLonga.isNegative()) {
            throw new IllegalArgumentException("as janelas da tendencia precisam ser positivas");
        }
        if (janelaCurta.compareTo(janelaLonga) >= 0) {
            // Com curta >= longa a razao e sempre 1 e a tendencia nao mede nada:
            // ela viraria uma constante com nome de sinal, que e pior do que nao
            // existir, porque parece estar funcionando.
            throw new IllegalArgumentException(
                    "a janela curta tem de ser menor que a longa, senao a razao e sempre 1");
        }
        if (janelaLonga.compareTo(janela) > 0) {
            // A janela longa da tendencia nao pode passar da janela de
            // acumulacao: la nao ha evento nenhum guardado, e a razao passaria a
            // ser calculada sobre um denominador truncado — subestimando o
            // passado e inflando a tendencia de todo mundo.
            throw new IllegalArgumentException(
                    "a janela longa da tendencia nao pode passar da janela de acumulacao");
        }
        if (pisoDaTendencia < 0) {
            throw new IllegalArgumentException("piso da tendencia negativo nao tem significado");
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
     *
     * <p>O piso do produto é 4,0 — dois cliques de hoje. Mais baixo que o da
     * jornada porque a evidência por produto é mais escassa; ver o cabeçalho da
     * classe.
     *
     * <p>O piso da tendência é 4,0 pelo mesmo raciocínio aplicado a outra coisa:
     * abaixo de dois cliques na janela de trinta dias, a razão entre as janelas é
     * ruído e a tendência devolve neutro.
     */
    public static PesosDeAfinidade padrao() {
        return new PesosDeAfinidade(
                Duration.ofDays(90), Duration.ofDays(7), 6.0, 4.0,
                Duration.ofDays(7), Duration.ofDays(30), 4.0);
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
     * Pontos acumulados viram afinidade de <strong>jornada</strong>, entre 0 e 1.
     *
     * <p>Limitado em 1 porque o fator do ranking é multiplicador com teto, e
     * deixar passar de 1 faria o teto depender de quantos cliques a pessoa deu —
     * o que é a definição de bolha.
     */
    public double afinidadeDe(double pontos) {
        return normalizar(pontos, pisoDeConfianca);
    }

    /** O mesmo, com o piso do nível do produto. */
    public double afinidadeDeProduto(double pontos) {
        return normalizar(pontos, pisoDeConfiancaDoProduto);
    }

    private static double normalizar(double pontos, double piso) {
        if (pontos <= 0) {
            return 0.0;
        }
        return Math.min(1.0, pontos / piso);
    }

    /**
     * O ponto neutro da tendência: a razão que atividade uniforme produziria.
     *
     * <p>Com 7 e 30 dias, é 0,233. Comparar a razão observada com este número é o
     * que transforma "23% dos pontos são da última semana" em "está do jeito
     * esperado" em vez de "está baixo".
     */
    public double tendenciaNeutra() {
        return (double) janelaCurta.toMillis() / janelaLonga.toMillis();
    }

    /**
     * A tendência, normalizada para que 1,0 seja neutro.
     *
     * <p>Acima de 1,0 a atividade está concentrada no presente; abaixo, está
     * esfriando. É devolvida assim, e não como a razão crua, porque o consumidor
     * é um multiplicador de ranking — e multiplicador quer "quanto a mais que o
     * normal", não "que fração do total".
     *
     * <p>Devolve exatamente 1,0 quando a evidência não chega ao piso. Ver o
     * cabeçalho da classe: razão de números pequenos é ruído, e um sinal de
     * ruído é pior que sinal nenhum porque ninguém desconfia dele.
     */
    public double tendenciaDe(double pontosCurta, double pontosLonga) {
        if (pontosLonga < pisoDaTendencia || pontosLonga <= 0) {
            return 1.0;
        }

        double razao = pontosCurta / pontosLonga;
        double neutra = tendenciaNeutra();

        if (neutra <= 0) {
            return 1.0;
        }
        return razao / neutra;
    }

    public Instant corte(Instant agora) {
        return agora.minus(janela);
    }

    public Instant corteCurto(Instant agora) {
        return agora.minus(janelaCurta);
    }

    public Instant corteLongo(Instant agora) {
        return agora.minus(janelaLonga);
    }
}
