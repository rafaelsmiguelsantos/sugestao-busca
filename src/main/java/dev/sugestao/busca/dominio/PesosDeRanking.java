package dev.sugestao.busca.dominio;

/**
 * Quanto cada sinal de ordenação pesa.
 *
 * <p>São multiplicadores, não somas, e isso é decisão: somar faria o peso
 * depender da escala do score do Solr, que muda com o tamanho do índice e com o
 * prefixo digitado. Multiplicar mantém a proporção — uma jornada líder sempre
 * vale {@code jornadaLider} vezes mais, tenha o índice mil ou um milhão de
 * termos.
 *
 * <p>{@code margemParaLiderar} existe para que empate não eleja ninguém. Quando
 * duas jornadas aparecem com força parecida, o prefixo é ambíguo — "limite"
 * serve a cartão e a Pix — e eleger uma delas por diferença de terceira casa
 * decimal é inventar uma intenção que o cliente não demonstrou.
 */
public record PesosDeRanking(
        double jornadaLider,
        double produtoDaJornada,
        double afinidadeMaxima,

        /**
         * O teto da afinidade por <em>produto</em>, menor que o da jornada.
         *
         * <p>Menor de propósito, e o motivo é o piso de confiança mais baixo do
         * outro lado: a afinidade por produto acredita com menos evidência — dois
         * cliques em vez de três — porque evidência por produto é escassa. Dar a
         * ela o mesmo teto da jornada seria somar as duas concessões e deixar
         * dois cliques num produto valerem mais que três cliques numa jornada.
         */
        double afinidadeDeProdutoMaxima,

        /**
         * O teto da tendência: quanto um interesse em alta pode promover.
         *
         * <p>Modesto, e deliberadamente o menor dos quatro. A tendência é o sinal
         * de que menos se confia — razão entre duas janelas, calculada sobre
         * pouca evidência na maioria dos casos — e o peso é onde essa
         * desconfiança fica escrita em número.
         */
        double tendenciaMaxima,

        /**
         * O teto do produto de todos os fatores de promoção.
         *
         * <p>Declarado em vez de emergente, e isso é o conserto de um problema
         * que a versão anterior tinha sem saber: o teto era 3,0 porque
         * 1,6 × 1,25 × 1,5 dá 3,0, e ninguém havia escrito 3,0 em lugar nenhum.
         * Acrescentar qualquer fator novo mudava o teto em silêncio — e esta
         * rodada acrescentou dois. Com o teto explícito, mexer num peso não move
         * o máximo do sistema sem alguém decidir isso.
         */
        double fatorMaximo,

        /**
         * O quanto a fadiga pode rebaixar: piso do fator, entre 0 e 1.
         *
         * <p>É o único peso desta classe abaixo de 1, e o único que rebaixa. Ver
         * {@link dev.sugestao.busca.dominio.agregado.PesosDeFadiga} para o que
         * isso contraria.
         */
        double fadigaMinima,

        double margemParaLiderar) {

    public PesosDeRanking {
        if (jornadaLider < 1.0 || produtoDaJornada < 1.0 || afinidadeMaxima < 1.0
                || afinidadeDeProdutoMaxima < 1.0 || tendenciaMaxima < 1.0) {
            throw new IllegalArgumentException(
                    "peso de promoção abaixo de 1 rebaixaria o candidato por um caminho que não "
                            + "registra motivo; para rebaixar existem a Peneira, que diz por quê, "
                            + "e a fadiga, que aparece no rastro");
        }
        if (fatorMaximo < 1.0) {
            throw new IllegalArgumentException(
                    "fator máximo abaixo de 1 transformaria toda promoção em rebaixamento");
        }
        if (fadigaMinima <= 0 || fadigaMinima > 1.0) {
            throw new IllegalArgumentException(
                    "fadiga mínima fora de (0,1]: em 0 ela zeraria a linha, que é remover sem "
                            + "dizer por quê; acima de 1 ela promoveria");
        }
    }

    /**
     * Os pesos de hoje. O teto é 3,6, e esse número foi corrigido por um teste.
     *
     * <p><strong>A primeira tentativa foi 3,0</strong> — o teto que o sistema
     * já tinha de fato, porque 1,6 × 1,25 × 1,5 dá exatamente 3,0. Parecia a
     * escolha conservadora: preservar o máximo que existia antes. Era um defeito,
     * e um teste de ranking o expôs em dois minutos.
     *
     * <p>Com teto 3,0, uma linha de jornada líder com produto e afinidade máxima
     * de jornada <em>já está no teto</em>. A afinidade por produto, que é o sinal
     * construído justamente para distinguir consignado de empréstimo dentro de
     * {@code credito}, ficava multiplicando um valor que o limite ia cortar de
     * volta: os dois candidatos saíam em 3,0 e o desempate não acontecia.
     * <strong>O recurso não ficava fraco, ficava inerte</strong> — e inerte para
     * exatamente quem tem mais comportamento registrado, que é quem ele existe
     * para servir.
     *
     * <p>3,6 é 1,6 × 1,25 × 1,5 × 1,2: o produto dos quatro fatores que
     * <em>precisam</em> caber. A tendência (1,15) fica acima dele e é cortada no
     * topo, o que é deliberado e é a decisão que o teto expressa: no limite, o
     * sinal de menos confiança é o primeiro a ser descartado.
     *
     * <p>O teto continua existindo para conter a bolha, e continua binding para
     * quem está no máximo de tudo. O que mudou é que ele deixou de silenciar um
     * sinal inteiro.
     */
    public static PesosDeRanking padrao() {
        return new PesosDeRanking(1.6, 1.25, 1.5, 1.2, 1.15, 3.6, 0.6, 0.15);
    }
}
