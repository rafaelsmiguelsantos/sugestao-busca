package dev.sugestao.busca.dominio.agregado;

import java.util.List;

/**
 * Um item que alguém curou: o termo, suas redações alternativas e a jornada a
 * que ele pertence.
 *
 * <p>Serve de régua para classificar o que vem do log. O catálogo é o único
 * lugar do sistema onde a relação entre palavra e jornada foi decidida por uma
 * pessoa — tudo o mais é inferência em cima dele.
 */
public record ItemCurado(String termo, List<String> sinonimos, String jornada) {

    public ItemCurado {
        sinonimos = sinonimos == null ? List.of() : List.copyOf(sinonimos);
    }
}
