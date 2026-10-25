package dev.sugestao.busca.dominio;

import java.util.Optional;

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

    /**
     * A leitura estrita, para o cadastro de oferta. Mesma razão de
     * {@link Relacionamento#talvez(String)}: no token, segmento desconhecido
     * virar {@code PF} é degradação aceitável; no cadastro, é uma oferta de
     * pessoa jurídica publicada para pessoa física sem ninguém ter decidido.
     */
    public static Optional<Segmento> talvez(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        return switch (texto.trim().toUpperCase()) {
            case "PF" -> Optional.of(PF);
            case "PJ" -> Optional.of(PJ);
            default -> Optional.empty();
        };
    }
}
