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

    /**
     * Se este candidato nasceu no índice — e portanto responde pelo piso de
     * k-anonimato e pela escala de score do motor de busca.
     *
     * <p>{@link #EVENTO} conta, e isso não é detalhe. Evento não é uma origem
     * própria: é um candidato do índice que o gatilho do perfil promoveu. Se a
     * promoção fizesse ele deixar de "vir do índice", duas coisas quebrariam de
     * uma vez — ele escaparia da rede de segurança do piso na {@link Peneira},
     * que é um controle de privacidade, e ficaria fora da normalização de
     * escala do {@link Rankeador}, aparecendo com o score bruto do Solr ao lado
     * de candidatos já reescalados para 0..1. O segundo só deixava o rastro
     * mentiroso, porque a cota garante a linha do evento de qualquer jeito. O
     * primeiro era um buraco de verdade.
     *
     * <p>O histórico do próprio cliente voltando para ele mesmo continua de
     * fora: não nasceu no índice e não é vazamento de ninguém.
     */
    public boolean veioDoIndice() {
        return this == CATALOGO || this == POPULAR || this == EVENTO;
    }
}
