package dev.sugestao.busca.adaptador.entrada.web;

/**
 * Exceção da borda, não do domínio: "não encontrei" é uma resposta HTTP, e o
 * domínio devolve {@code Optional} porque para ele a ausência não é erro.
 */
public class OfertaNaoEncontrada extends RuntimeException {

    private final String ofertaId;

    public OfertaNaoEncontrada(String ofertaId) {
        super("oferta nao encontrada: " + ofertaId);
        this.ofertaId = ofertaId;
    }

    public String ofertaId() {
        return ofertaId;
    }
}
