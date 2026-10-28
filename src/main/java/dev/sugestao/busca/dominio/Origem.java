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
    POPULAR(3),

    /**
     * A linha de jornada que entra quando a peneira zerou a lista. Não vem do
     * índice e não disputa posição com ninguém: ela só existe quando não há mais
     * nada, e sai sozinha.
     *
     * <p>Tem origem própria em vez de se disfarçar de {@link #CATALOGO} por três
     * motivos, e o terceiro é o que decidiu. Um: quem consome a resposta precisa
     * saber que esta linha não casou com o que a pessoa digitou — ela casou com a
     * <em>jornada</em> do que foi removido. Dois: a métrica de desfecho só
     * consegue separar "recuperada" de "encontrou pouco" se a linha se declarar.
     * Três: sem origem própria, a única forma de contar quantas vezes o recurso
     * agiu seria inferir — e um recurso que se mede por inferência é um recurso
     * que ninguém mede.
     *
     * <p>A prioridade é a mais fraca do conjunto e <strong>hoje não é
     * exercida</strong>: a substituta entra depois do {@link Misturador}, então
     * nunca passa pela deduplicação nem pelas cotas. O número está aqui para o
     * dia em que isso mudar, e aponta para o lado seguro — perder a linha
     * substituta numa colisão é o resultado certo, porque qualquer outra origem
     * casou de verdade com o prefixo.
     */
    SUBSTITUTO(4);

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
