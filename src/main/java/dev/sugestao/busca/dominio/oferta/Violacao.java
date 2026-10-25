package dev.sugestao.busca.dominio.oferta;

/**
 * Uma regra violada no cadastro, endereçada ao campo que a violou.
 *
 * <p>{@code codigo} existe separado de {@code mensagem} porque os dois têm
 * leitores diferentes. A mensagem é para a pessoa que está integrando e lê o
 * log; o código é para o programa dela, que precisa distinguir "esqueci o campo"
 * de "mandei no formato errado" sem casar texto em português. Texto de mensagem
 * muda quando a redação melhora; código é contrato.
 */
public record Violacao(String campo, String codigo, String mensagem) {

    public static final String OBRIGATORIO = "obrigatorio";
    public static final String FORMATO = "formato";
    public static final String FORA_DO_DOMINIO = "fora_do_dominio";
    public static final String RESERVADO = "reservado";
    public static final String ORDEM_INVALIDA = "ordem_invalida";
    public static final String ACIMA_DO_TETO = "acima_do_teto";

    public static Violacao obrigatorio(String campo) {
        return new Violacao(campo, OBRIGATORIO, "e obrigatorio");
    }

    public static Violacao formato(String campo, String esperado) {
        return new Violacao(campo, FORMATO, "fora do formato esperado: " + esperado);
    }

    public static Violacao foraDoDominio(String campo, String recebido, String aceitos) {
        return new Violacao(campo, FORA_DO_DOMINIO,
                "valor '" + recebido + "' nao reconhecido; aceitos: " + aceitos);
    }

    public static Violacao reservado(String campo, String motivo) {
        return new Violacao(campo, RESERVADO, motivo);
    }

    public static Violacao acimaDoTeto(String campo, int teto) {
        return new Violacao(campo, ACIMA_DO_TETO, "aceita no maximo " + teto + " valores");
    }
}
