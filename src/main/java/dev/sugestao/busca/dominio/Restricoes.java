package dev.sugestao.busca.dominio;

/**
 * O que o documento declara que o cliente precisa ter — ou não ter — para que
 * esta linha possa aparecer para ele.
 *
 * <p>Está agrupado num valor só porque as três perguntas são da mesma natureza:
 * todas dependem do perfil, todas falham fechadas, e todas são declaradas pelo
 * negócio no catálogo em vez de codificadas aqui. Quando aparecer a quarta, ela
 * entra neste record e não na assinatura de {@link Candidato}.
 */
public record Restricoes(
        boolean exigeElegibilidade,
        boolean vetaInadimplencia,
        boolean exigeContaAtiva) {

    public static final Restricoes NENHUMA = new Restricoes(false, false, false);

    public boolean algumaDependeDoPerfil() {
        return exigeElegibilidade || vetaInadimplencia || exigeContaAtiva;
    }
}
