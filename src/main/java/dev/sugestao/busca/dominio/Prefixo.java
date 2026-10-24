package dev.sugestao.busca.dominio;

/**
 * O que o cliente digitou, já normalizado. Nasce válido ou não nasce: nenhum
 * outro ponto do sistema precisa lembrar de normalizar antes de consultar.
 *
 * <p>O piso de caracteres não é estético. Com um caractere, o leque de candidatos
 * é o índice inteiro e o prazo de 18 ms não se sustenta.
 */
public record Prefixo(String valor) {

    public Prefixo {
        valor = Normalizacao.aplicar(valor);
    }

    public static Prefixo de(String cru) {
        return new Prefixo(cru);
    }

    public boolean curtoDemais(int minimo) {
        return valor.length() < minimo;
    }

    /** O tamanho entra no modelo da camada 4 como {@code efi.prefixo_len}. */
    public int tamanho() {
        return valor.length();
    }
}
