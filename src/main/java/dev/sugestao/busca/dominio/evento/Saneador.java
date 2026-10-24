package dev.sugestao.busca.dominio.evento;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O portão de saneamento. Decide se um termo digitado pode sequer ser guardado.
 *
 * <p>Num banco o campo de busca recebe CPF, número de conta, nome de
 * beneficiário e chave Pix. Tudo isso entra no log, e um índice de sugestão
 * construído sobre o log devolve essas coisas a quem digitar as primeiras
 * letras. Este portão é o que impede a construção desse índice.
 *
 * <p><strong>Descarta o termo inteiro, nunca mascara.</strong> Mascarar o CPF e
 * guardar o resto da frase guarda o resto da frase — e é no resto que costuma
 * estar o nome. Pelo mesmo motivo o descarte acontece na <em>ingestão</em> e não
 * na publicação: o log bruto também não deveria guardar.
 *
 * <p><strong>O que este portão não pega, e é de propósito.</strong> Nenhuma
 * expressão regular separa "maria silva" de "maria da penha lei". Nome próprio
 * não tem padrão, e tentar adivinhá-lo por lista de nomes descartaria metade do
 * vocabulário legítimo. Quem pega esse caso é o piso de clientes distintos, lá
 * na publicação — por isso ele é o portão, e este aqui é o complemento.
 */
public final class Saneador {

    /** Abaixo disto, uma sequência de dígitos é ano, valor ou quantidade. */
    private static final int DIGITOS_SUSPEITOS = 6;

    private static final Pattern EMAIL =
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    /** Chave Pix aleatória. */
    private static final Pattern UUID_PIX = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    /**
     * Uma corrida de dígitos, aceitando os separadores com que as pessoas
     * digitam documento: ponto, hífen, barra e espaço. Sem isso, "123.456.789-00"
     * seria lido como quatro números curtos e passaria pelo portão inteiro.
     */
    private static final Pattern CORRIDA_DE_DIGITOS =
            Pattern.compile("\\d(?:[\\s.\\-/]*\\d)*");

    public Saneamento avaliar(String termo) {
        if (termo == null || termo.isBlank()) {
            return Saneamento.descartado("TERMO_VAZIO");
        }
        if (EMAIL.matcher(termo).find()) {
            return Saneamento.descartado("EMAIL");
        }
        if (UUID_PIX.matcher(termo).find()) {
            return Saneamento.descartado("CHAVE_PIX_ALEATORIA");
        }

        Matcher corridas = CORRIDA_DE_DIGITOS.matcher(termo);
        while (corridas.find()) {
            String digitos = corridas.group().replaceAll("\\D", "");

            if (digitos.length() == 11 && cpfValido(digitos)) {
                return Saneamento.descartado("CPF");
            }
            if (digitos.length() == 14 && cnpjValido(digitos)) {
                return Saneamento.descartado("CNPJ");
            }
            if (digitos.length() >= 13 && digitos.length() <= 19 && luhnValido(digitos)) {
                return Saneamento.descartado("CARTAO");
            }
            // O que sobra apanha agência e conta, telefone, e todo documento
            // malformado que os testes de dígito deixariam passar. É deliberado
            // que este corte seja o mais largo: o custo de um falso positivo é
            // uma sugestão a menos, e o de um falso negativo é o dado de uma
            // pessoa na tela de outra.
            if (digitos.length() >= DIGITOS_SUSPEITOS) {
                return Saneamento.descartado("SEQUENCIA_NUMERICA_LONGA");
            }
        }
        return Saneamento.ACEITO;
    }

    // -----------------------------------------------------------------------

    private static boolean cpfValido(String d) {
        if (d.chars().distinct().count() == 1) {
            return false;
        }
        return digitoCpf(d, 9, 10) == d.charAt(9) - '0'
                && digitoCpf(d, 10, 11) == d.charAt(10) - '0';
    }

    private static int digitoCpf(String d, int ate, int pesoInicial) {
        int soma = 0;
        for (int i = 0; i < ate; i++) {
            soma += (d.charAt(i) - '0') * (pesoInicial - i);
        }
        int resto = 11 - (soma % 11);
        return resto >= 10 ? 0 : resto;
    }

    private static boolean cnpjValido(String d) {
        if (d.chars().distinct().count() == 1) {
            return false;
        }
        int[] primeiro = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] segundo = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

        return digitoCnpj(d, primeiro) == d.charAt(12) - '0'
                && digitoCnpj(d, segundo) == d.charAt(13) - '0';
    }

    private static int digitoCnpj(String d, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) {
            soma += (d.charAt(i) - '0') * pesos[i];
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private static boolean luhnValido(String d) {
        int soma = 0;
        boolean dobra = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if (dobra) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            soma += n;
            dobra = !dobra;
        }
        return soma % 10 == 0;
    }
}
