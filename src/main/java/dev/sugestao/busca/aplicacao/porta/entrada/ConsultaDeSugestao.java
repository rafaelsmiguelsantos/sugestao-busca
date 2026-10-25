package dev.sugestao.busca.aplicacao.porta.entrada;

import dev.sugestao.busca.dominio.Cliente;

import java.util.Objects;

/**
 * O comando do caso de uso. Recebe o prefixo exatamente como veio do teclado —
 * a normalização é regra de domínio e acontece lá dentro, não na borda.
 */
public record ConsultaDeSugestao(String prefixoDigitado, Cliente cliente, int limiteSolicitado) {

    public ConsultaDeSugestao {
        Objects.requireNonNull(cliente, "cliente");
    }

    /**
     * Sem limite pedido: usa o configurado.
     *
     * <p>Zero e não um {@code Integer} nulo. A ausência de limite e o pedido de
     * "nenhuma linha" não são estados distintos que valha a pena representar —
     * uma lista de zero sugestões não é algo que alguém queira pedir — e
     * {@code Optional} num comando chamado a cada tecla é alocação sem
     * contrapartida.
     */
    public ConsultaDeSugestao(String prefixoDigitado, Cliente cliente) {
        this(prefixoDigitado, cliente, 0);
    }
}
