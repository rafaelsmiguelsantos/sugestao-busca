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
        double margemParaLiderar) {

    public PesosDeRanking {
        if (jornadaLider < 1.0 || produtoDaJornada < 1.0 || afinidadeMaxima < 1.0) {
            throw new IllegalArgumentException(
                    "peso abaixo de 1 rebaixaria o candidato em vez de promovê-lo; "
                            + "para rebaixar existe a Peneira, que também diz por quê");
        }
    }

    public static PesosDeRanking padrao() {
        return new PesosDeRanking(1.6, 1.25, 1.5, 0.15);
    }
}
