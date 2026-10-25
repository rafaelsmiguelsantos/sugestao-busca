package dev.sugestao.busca.dominio;

/**
 * De onde o <em>dado</em> veio — distinto de {@link Origem}, que é onde a linha
 * aparece.
 *
 * <p>As duas coisas moravam juntas em {@code Origem.veioDoIndice()}, e a conta
 * fechava enquanto todo item do índice era publicado com contador de clientes
 * distintos. Ela para de fechar no momento em que uma jornada cadastra uma
 * oferta nova pela API: a oferta existe, é elegível, e tem zero clientes
 * distintos — porque ninguém a buscou ainda. Com o piso aplicado a tudo, ela
 * ficaria invisível até acumular trinta buscas que nunca vão acontecer, porque
 * ela não aparece. O catálogo semeado hoje escapa disso escrevendo contador alto
 * falso em cada item, o que é uma mentira no dado para contornar uma regra
 * aplicada no lugar errado.
 *
 * <p>O piso de k-anonimato existe para proteger <strong>termo digitado por
 * gente</strong>: um termo que só três pessoas buscaram, devolvido a um quarto,
 * conta algo sobre aquelas três. Nome de produto que o banco oferece não conta
 * nada sobre ninguém — não há sujeito para reidentificar. Por isso a pergunta
 * certa não é "veio do índice?", é "veio de comportamento de cliente?".
 *
 * <p>É deliberadamente imutável sob promoção. {@link Origem} é sobrescrita
 * quando um gatilho promove um candidato a {@link Origem#EVENTO}; a procedência
 * não, e {@link Candidato#com(Origem)} a preserva. Essa foi a falha que
 * {@code eventoTambemRespondePeloPiso} passou a guardar: promover apagava a
 * informação de que o termo tinha nascido no log, e o piso deixava de valer
 * justamente para a linha mais destacada da lista.
 */
public enum Procedencia {

    /**
     * Agregado do log de busca de muitos clientes. Responde pelo piso de
     * k-anonimato, sempre, promovido ou não.
     */
    LOG,

    /**
     * Curado por uma pessoa no catálogo, ou cadastrado por uma jornada via
     * {@code POST /v1/ofertas}. Não responde pelo piso: não há comportamento de
     * cliente aqui, há decisão de produto.
     */
    CATALOGO,

    /**
     * O histórico da própria pessoa, voltando para ela mesma. Não passa pelo
     * índice e não é vazamento de ninguém.
     */
    CLIENTE;

    /**
     * Se o piso de k-anonimato se aplica a este candidato.
     *
     * <p>Falha fechada por construção: só {@link #CATALOGO} e {@link #CLIENTE}
     * dispensam o piso, e as duas dispensam por motivo declarado. Qualquer
     * procedência nova nasce respondendo pelo piso só se for escrita aqui — o
     * que força a decisão a ser tomada em vez de herdada.
     */
    public boolean respondePeloPisoDeKAnonimato() {
        return this == LOG;
    }

    /**
     * Se a relevância deste candidato é score do motor de busca, e portanto
     * precisa ser reescalada antes de concorrer com as outras origens.
     *
     * <p>Era o outro uso de {@code veioDoIndice()}, e este continua valendo para
     * catálogo: oferta cadastrada também é pontuada pelo Solr.
     */
    public boolean temScoreDoIndice() {
        return this == LOG || this == CATALOGO;
    }
}
