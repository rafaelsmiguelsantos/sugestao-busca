package dev.sugestao.busca.dominio;

import java.util.Objects;

/**
 * Identidade e segmento, nada mais. Tudo o que é comportamento mora no
 * {@link Perfil}, que é lido à parte e pode faltar.
 */
public record Cliente(String id, Segmento segmento) {

    public Cliente {
        Objects.requireNonNull(id, "id do cliente");
        Objects.requireNonNull(segmento, "segmento do cliente");
    }
}
