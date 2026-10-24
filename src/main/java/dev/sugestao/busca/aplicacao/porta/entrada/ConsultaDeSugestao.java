package dev.sugestao.busca.aplicacao.porta.entrada;

import dev.sugestao.busca.dominio.Cliente;

import java.util.Objects;

/**
 * O comando do caso de uso. Recebe o prefixo exatamente como veio do teclado —
 * a normalização é regra de domínio e acontece lá dentro, não na borda.
 */
public record ConsultaDeSugestao(String prefixoDigitado, Cliente cliente) {

    public ConsultaDeSugestao {
        Objects.requireNonNull(cliente, "cliente");
    }
}
