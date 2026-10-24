package dev.sugestao.busca.dominio.evento;

/**
 * O que o app registra. São quatro, e cada um existe por um motivo diferente.
 *
 * <p>A assimetria entre {@link #SUGESTAO_EXIBIDA} e {@link #SUGESTAO_CLICADA} é
 * o ponto. Sem impressão registrada não há denominador: clique sobre clique mede
 * popularidade, não acerto. E sem a posição junto da impressão, um modelo
 * treinado nesses dados aprende que a primeira linha é a melhor, quando ela é
 * apenas onde a linha estava.
 */
public enum TipoDeEvento {

    /** A pessoa digitou e submeteu uma busca. Alimenta a camada popular. */
    BUSCA_DIGITADA,

    /** Uma lista foi mostrada. Carrega os termos e a posição de cada um. */
    SUGESTAO_EXIBIDA,

    /** A pessoa tocou numa das linhas. Carrega a posição em que ela estava. */
    SUGESTAO_CLICADA,

    /** A jornada terminou em contratação. É o numerador da taxa de adesão, e o
     *  único destes quatro que o negócio reconhece como resultado. */
    ADESAO
}
