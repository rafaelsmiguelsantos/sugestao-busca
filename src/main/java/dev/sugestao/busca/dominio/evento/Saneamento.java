package dev.sugestao.busca.dominio.evento;

/**
 * O veredito do portão sobre um termo digitado.
 *
 * <p>O termo é aceito inteiro ou descartado inteiro. Não há "mascarar e
 * guardar": mascarar um CPF e manter o resto da frase guarda o resto da frase, e
 * é no resto que costuma estar o nome. O desenho é explícito — o padrão
 * conhecido é descartado <em>na ingestão</em>, não no índice, porque o log bruto
 * também não deveria guardar.
 */
public record Saneamento(boolean aceito, String motivo) {

    public static final Saneamento ACEITO = new Saneamento(true, "");

    public static Saneamento descartado(String motivo) {
        return new Saneamento(false, motivo);
    }
}
