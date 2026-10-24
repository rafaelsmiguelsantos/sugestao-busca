package dev.sugestao.busca.dominio;

/**
 * Como está a conta agora.
 *
 * <p>Ao contrário do segmento e da faixa, isto muda de um dia para o outro e não
 * cabe num token: vem do perfil, e por isso vira corte na {@link Peneira} e não
 * {@code fq} no índice.
 *
 * <p>{@link #DESCONHECIDA} é o valor do perfil neutro, e é ele que define o lado
 * seguro da degradação: sem saber como está a conta, a oferta que depende do
 * estado dela não aparece. Falha fechada — oferecer crédito novo a quem está
 * inadimplente é pior do que não oferecer nada a quem poderia contratar.
 */
public enum SituacaoDaConta {

    ATIVA,

    /** Conta negativa: em atraso. Não bloqueia a busca, bloqueia oferta de
     *  crédito novo — e abre a jornada de renegociação. */
    INADIMPLENTE,

    BLOQUEADA,

    ENCERRADA,

    /** O perfil não chegou, ou o cliente não tem perfil montado. */
    DESCONHECIDA;

    public boolean operante() {
        return this == ATIVA;
    }

    public boolean inadimplente() {
        return this == INADIMPLENTE;
    }

    public static SituacaoDaConta de(String texto) {
        if (texto == null || texto.isBlank()) {
            return DESCONHECIDA;
        }
        try {
            return valueOf(texto.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DESCONHECIDA;
        }
    }
}
