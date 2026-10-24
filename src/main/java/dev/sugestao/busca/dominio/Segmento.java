package dev.sugestao.busca.dominio;

/**
 * Vem do token, não do perfil. É a condição que permite ao leque disparar a
 * consulta ao Solr sem esperar a leitura do perfil — se o segmento estivesse no
 * perfil, as duas chamadas virariam uma fila e o orçamento de 30 ms acabaria.
 */
public enum Segmento {
    PF,
    PJ;

    public static Segmento de(String texto) {
        if (texto == null || texto.isBlank()) {
            return PF;
        }
        return switch (texto.trim().toUpperCase()) {
            case "PJ" -> PJ;
            default -> PF;
        };
    }
}
