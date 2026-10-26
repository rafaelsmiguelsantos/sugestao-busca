package dev.sugestao.busca.aplicacao;

/**
 * O que aconteceu com a segunda tentativa, a que tolera erro de digitação.
 *
 * <p>É um dos valores que a medição mais precisa ver, e por um motivo específico:
 * busca tolerante é a única melhoria desta rodada que pode <em>piorar</em> a
 * precisão enquanto melhora a cobertura. Ela faz buscas que devolviam zero
 * passarem a devolver algo — e "algo" pode ser o produto errado. Sem separar
 * "acionou e ganhou" de "acionou e não ganhou", o único número visível seria a
 * queda da taxa de zero-resultado, que subiria mesmo se metade das recuperações
 * fosse lixo.
 *
 * <p>Por isso não é um booleano.
 */
public enum ToleranciaAplicada {

    /** Desligada por configuração. */
    DESLIGADA,

    /**
     * O prefixo não tem tamanho para isso.
     *
     * <p>Com três ou quatro letras, distância de edição 1 casa quase tudo:
     * {@code car} alcança {@code cor}, {@code casa} e {@code card}. Abaixo do
     * mínimo a tolerância não é tentada — não por economia, mas porque o
     * resultado seria pior que a lista vazia que ela substituiria.
     */
    NAO_ELEGIVEL,

    /**
     * A consulta exata já trouxe o bastante.
     *
     * <p>Este é o caso comum, e é o que mantém o custo da tolerância em zero no
     * caminho quente: a segunda ida ao índice só acontece quando a primeira
     * falhou, que é exatamente quando não há nada a perder.
     */
    NAO_PRECISOU,

    /** Acionada, e trouxe candidato que a consulta exata não tinha. */
    ACIONADA_COM_GANHO,

    /**
     * Acionada e não trouxe nada.
     *
     * <p>Erro de digitação longe demais, ou palavra que não existe no
     * vocabulário. O segundo caso é informação de produto: {@code grana} não
     * casa com nada porque falta sinônimo, e nenhuma distância de edição
     * conserta falta de vocabulário.
     */
    ACIONADA_SEM_GANHO
}
