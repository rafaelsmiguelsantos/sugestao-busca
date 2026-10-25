package dev.sugestao.busca.dominio;

/**
 * Uma linha pronta para a tela — e, quando é oferta, pronta para ser acionada
 * por programa. A origem sai junto porque o app precisa dela para registrar a
 * impressão, e é a impressão com posição que torna a camada 4 treinável depois.
 *
 * <p>Esta classe atende dois consumidores com exigências diferentes, e é por
 * isso que ela carrega mais do que a tela usa. O app precisa de {@code rotulo} e
 * {@code destino}: texto e caminho. Uma jornada que chama esta API para decidir
 * o que oferecer precisa de {@code ofertaId}, {@code jornada} e
 * {@code produtoAlvo} — identidade, intenção e produto. Os três últimos não
 * aparecem em tela nenhuma.
 *
 * <p>Separar em dois tipos de retorno foi considerado e descartado: seriam duas
 * listas a manter em ordem idêntica, e a posição é o que liga a impressão ao
 * aprendizado. Uma lista só, com campos que cada consumidor ignora à vontade,
 * mantém a posição sendo um fato único.
 */
public record Sugestao(
        String ofertaId,
        String termo,
        String rotulo,
        String destino,
        String jornada,
        String produtoAlvo,
        double relevancia,
        Origem origem) {

    public Sugestao {
        ofertaId = ofertaId == null ? "" : ofertaId;
    }

    /**
     * Esta linha identifica uma oferta acionável.
     *
     * <p>Mesma regra do {@link Candidato#ehOferta()}, e de propósito: a resposta
     * não pode anunciar como oferta algo que a peneira e o ranking não trataram
     * como oferta. Se as duas definições divergirem, o consumidor recebe
     * identificador de coisa que o resto do fluxo não sabe julgar.
     */
    public boolean ehOferta() {
        return ofertaId != null && !ofertaId.isBlank()
                && produtoAlvo != null && !produtoAlvo.isBlank();
    }
}
