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
    ALEM_DO_LIMITE;

    /**
     * Este motivo diz que a linha existia, casava com o que a pessoa digitou, e
     * saiu por uma <strong>condição desta pessoa</strong>.
     *
     * <p>É a pergunta que o {@link Recuperador} faz, e a resposta separa dois
     * tipos de lista vazia que nada mais no sistema separa:
     *
     * <ul>
     *   <li><em>Não há nada.</em> O prefixo não casou com documento nenhum, ou o
     *       que casou saiu por cota, duplicação ou teto de lista. Aqui a lista
     *       vazia é a resposta certa e não há jornada para oferecer alternativa —
     *       inventar uma seria devolver algo que ninguém procurou.</li>
     *   <li><em>Há, e esta pessoa não pode.</em> A busca encontrou a coisa certa e
     *       a peneira a tirou com razão. A lista vazia aqui é o defeito: a
     *       jornada é conhecida, e existe caminho dentro dela que não exige nada.</li>
     * </ul>
     *
     * <p><strong>{@link #ABAIXO_DO_PISO} fica de fora, e é a exclusão que
     * importa.</strong> Se um termo de log abaixo do piso de k-anonimato gerasse
     * linha substituta, a presença da substituta informaria que <em>algo casou
     * com aquele prefixo</em> — e esconder exatamente isso é a única coisa que o
     * piso faz. O recurso que recupera lista vazia não pode ser o canal lateral
     * que desfaz o controle de privacidade.
     *
     * <p>{@link #PRODUTO_JA_CONTRATADO} fica dentro: quem já tem o produto e
     * digita o nome dele continua tendo interesse na jornada, e hoje recebe nada.
     */
    public boolean condicionalDoCliente() {
        return switch (this) {
            case PRODUTO_JA_CONTRATADO, SEM_ELEGIBILIDADE,
                 CONTA_INADIMPLENTE, CONTA_NAO_OPERANTE -> true;
            case ABAIXO_DO_PISO, TERMO_DUPLICADO, COTA_DE_HISTORICO,
                 COTA_DE_EVENTO, ALEM_DO_LIMITE -> false;
        };
    }
}
