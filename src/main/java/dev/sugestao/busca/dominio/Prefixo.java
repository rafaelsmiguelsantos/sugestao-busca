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

    /**
     * O teto, que existe por custo e não por relevância.
     *
     * <p>O analisador de prefixo roda {@code EdgeNGram} sobre o texto da
     * consulta e o {@code edismax} quebra em palavras: um "prefixo" de dez mil
     * caracteres é trabalho real de CPU no Solr, repetido a cada tecla, por
     * requisição que ninguém digitou de verdade. Ninguém busca com oitenta
     * caracteres; quem manda oitenta caracteres não está buscando.
     *
     * <p>Não vira erro. Passa do teto e a resposta é lista vazia, o mesmo que
     * prefixo curto demais — porque a regra desta borda é que a caixa de busca
     * nunca recebe erro, e porque devolver {@code 400} aqui contaria ao
     * chamador exatamente onde está o limite que ele deveria respeitar.
     */
    public boolean longoDemais(int maximo) {
        return valor.length() > maximo;
    }

    /** O tamanho entra no modelo da camada 4 como {@code efi.prefixo_len}. */
    public int tamanho() {
        return valor.length();
    }
}
