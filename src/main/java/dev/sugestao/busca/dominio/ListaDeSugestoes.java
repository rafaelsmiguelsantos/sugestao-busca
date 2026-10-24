package dev.sugestao.busca.dominio;

import java.util.List;

/**
 * O resultado. A lista vazia é resposta legítima — prefixo curto demais, ou
 * nada sobreviveu à peneira — e nunca um erro para o cliente.
 */
public record ListaDeSugestoes(List<Sugestao> itens, boolean degradada) {

    public ListaDeSugestoes {
        itens = itens == null ? List.of() : List.copyOf(itens);
    }

    public static ListaDeSugestoes vazia() {
        return new ListaDeSugestoes(List.of(), false);
    }
}
