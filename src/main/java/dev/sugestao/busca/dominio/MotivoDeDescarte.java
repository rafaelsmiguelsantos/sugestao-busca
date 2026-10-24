package dev.sugestao.busca.dominio;

/**
 * Por que um candidato não virou linha.
 *
 * <p>Existe para que a lista final seja auditável. Sem motivo registrado, a
 * única coisa observável é o que apareceu — e a pergunta que importa numa
 * camada de sugestão quase sempre é por que algo <em>não</em> apareceu.
 */
public enum MotivoDeDescarte {

    /** O cliente já tem o produto. Oferecer de novo é ruído. */
    PRODUTO_JA_CONTRATADO,

    /** Exige elegibilidade que o perfil não concede. Falha fechada. */
    SEM_ELEGIBILIDADE,

    /** Crédito novo para conta em atraso. O caminho para esse cliente é a
     *  renegociação, que entra por gatilho, não por oferta. */
    CONTA_INADIMPLENTE,

    /** Operação que exige conta operante, e a conta não está — ou não se sabe. */
    CONTA_NAO_OPERANTE,

    /** Abaixo do piso de k-anonimato. Rede de segurança do BFF: se chegou
     *  aqui, a coleção foi publicada com o invariante violado. */
    ABAIXO_DO_PISO,

    /** Outra redação da mesma intenção já ocupou a linha. */
    TERMO_DUPLICADO,

    /** O histórico já gastou a cota dele. */
    COTA_DE_HISTORICO,

    /** A linha do evento já estava ocupada. Defensivo: o classificador promove
     *  no máximo o que cabe na cota, então na prática não deve acontecer. */
    COTA_DE_EVENTO,

    /** Sobrou relevância, acabaram as posições. */
    ALEM_DO_LIMITE
}
