package dev.sugestao.busca.dominio;

/**
 * De onde a linha veio. Existe para a cota: sem origem declarada, a lista é um
 * ranking único e colapsa na fonte mais popular.
 *
 * <p>A prioridade não ordena a lista — isso é trabalho do score. Ela só decide
 * quem sobrevive quando duas origens trazem o mesmo termo.
 */
public enum Origem {

    /** Gatilho recente — Pix recusado há duas horas vira "antecipar limite".
     *  Janela curta, expira sozinho no perfil. */
    EVENTO(0),

    /** O que este cliente já buscou. Vem do registro de perfil, não do índice:
     *  custo zero na requisição e nenhum risco de vazamento. */
    HISTORICO(1),

    /** Entidade nomeada: produto, artigo de ajuda, ação direta. Vem do índice,
     *  com vigência e elegibilidade declaradas. */
    CATALOGO(2),

    /** O que gente de segmento e carteira parecidos busca. Vem do índice, e só
     *  depois do piso de k-anonimato. */
    POPULAR(3);

    private final int prioridade;

    Origem(int prioridade) {
        this.prioridade = prioridade;
    }

    public int prioridade() {
        return prioridade;
    }

    /*
     * Aqui existia veioDoIndice(), e ele respondia a duas perguntas ao mesmo
     * tempo: "o piso de k-anonimato se aplica?" e "a relevancia e score do Solr
     * e precisa ser reescalada?". As duas tinham a mesma resposta enquanto todo
     * item do indice vinha do log ou de catalogo com contador falso. Deixaram de
     * ter quando uma jornada passou a cadastrar oferta pela API: oferta nova tem
     * zero clientes distintos e score do Solr — resposta "nao" para a primeira
     * pergunta e "sim" para a segunda. As duas agora moram em Procedencia, que
     * nao e sobrescrita por promocao.
     */
}
