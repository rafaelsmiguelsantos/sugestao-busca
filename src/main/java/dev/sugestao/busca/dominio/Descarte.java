package dev.sugestao.busca.dominio;

/** Um candidato que não virou linha, e a razão. */
public record Descarte(String termo, Origem origem, MotivoDeDescarte motivo) {

    public static Descarte de(Candidato candidato, MotivoDeDescarte motivo) {
        return new Descarte(candidato.termo(), candidato.origem(), motivo);
    }
}
