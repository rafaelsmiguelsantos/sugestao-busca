package dev.sugestao.busca.dominio;

/**
 * Um candidato que não virou linha, e a razão.
 *
 * <p>A <strong>jornada</strong> entra aqui para o {@link Recuperador} poder
 * responder "de que jornada era a coisa que acabou de ser removida?" sem
 * precisar da lista de candidatos original. Ela não aparece na explicação HTTP:
 * o que o descarte conta sobre a pessoa já era sensível com termo e motivo, e
 * acrescentar a jornada ao JSON não ajuda ninguém a depurar e ajuda quem não
 * devia estar lendo.
 */
public record Descarte(String termo, String jornada, Origem origem, MotivoDeDescarte motivo) {

    public Descarte {
        jornada = jornada == null ? "" : jornada;
    }

    public static Descarte de(Candidato candidato, MotivoDeDescarte motivo) {
        return new Descarte(candidato.termo(), candidato.jornada(), candidato.origem(), motivo);
    }
}
