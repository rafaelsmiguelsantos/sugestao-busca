package dev.sugestao.busca.aplicacao;

/**
 * O resultado de esperar por uma dependência dentro do prazo: o valor, e se ele
 * é o de verdade ou o caminho alternativo.
 *
 * <p>Sem este envelope, o caso de uso devolve a lista degradada sem conseguir
 * dizer por quê — e "degradada" sozinho não diz se a dependência demorou, se ela
 * respondeu com erro, ou se o cliente simplesmente não tem perfil montado. São
 * três incidentes diferentes, e dois deles nem são incidente.
 */
public record Colheita<T>(T valor, Motivo motivo) {

    public enum Motivo {
        /** Respondeu dentro do prazo. */
        RESPONDEU,
        /** Estourou o prazo. Nenhuma nova tentativa: a próxima tecla já vem a caminho. */
        PRAZO_VENCIDO,
        /** Respondeu com falha. Para o cliente é o mesmo que não ter respondido. */
        FALHOU,
        /** A thread foi interrompida enquanto esperava. */
        INTERROMPIDA
    }

    public static <T> Colheita<T> respondeu(T valor) {
        return new Colheita<>(valor, Motivo.RESPONDEU);
    }

    public boolean usouAlternativa() {
        return motivo != Motivo.RESPONDEU;
    }
}
