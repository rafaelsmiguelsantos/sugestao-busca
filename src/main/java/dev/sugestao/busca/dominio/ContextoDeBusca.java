package dev.sugestao.busca.dominio;

/**
 * O que o token sabe e o índice pode receber: segmento e faixa, sem identidade.
 *
 * <p>Existe para tornar a regra explícita no tipo. A consulta ao índice de
 * sugestões nunca leva quem está perguntando — ela leva apenas atributos de
 * coorte. Se a assinatura da porta aceitasse {@link Cliente}, nada impediria o
 * adaptador de mandar o identificador junto, e a separação entre "o que uma
 * pessoa digitou" e "o que outra vê sugerido" passaria a depender de disciplina
 * em vez de depender do compilador.
 */
public record ContextoDeBusca(Segmento segmento, Relacionamento relacionamento) {

    public ContextoDeBusca {
        segmento = segmento == null ? Segmento.PF : segmento;
        relacionamento = relacionamento == null ? Relacionamento.VAREJO : relacionamento;
    }
}
