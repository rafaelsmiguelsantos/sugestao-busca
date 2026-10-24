package dev.sugestao.busca.dominio.agregado;

import java.time.Instant;
import java.util.Set;

/**
 * Um termo somado sobre a janela, pronto para ser julgado pelo portão de
 * publicação.
 *
 * <p>{@code clientesDistintos} é contado por identificador de cliente, não por
 * sessão. Um termo que uma pessoa digitou cinquenta vezes continua sendo dado de
 * uma pessoa só, e contar sessão transformaria insistência em consenso — que é
 * exatamente o buraco que o piso de k-anonimato existe para fechar.
 *
 * <p>O CTR tem {@code exibicoes} no denominador, não buscas. Clique sobre busca
 * mede popularidade do termo; clique sobre impressão mede se a sugestão acertou.
 * São perguntas diferentes, e só a segunda diz se esta camada está funcionando.
 * É por isso que a impressão precisa ser registrada mesmo quando ninguém clica.
 */
public record TermoAgregado(
        String termo,
        Set<String> segmentos,
        int clientesDistintos,
        int frequencia,
        int exibicoes,
        int cliques,
        int adesoes,
        Instant vistoEm) {

    public TermoAgregado {
        segmentos = segmentos == null || segmentos.isEmpty() ? Set.of("PF") : Set.copyOf(segmentos);
    }

    /** Clique sobre impressão. Zero exibições significa "ainda não sei", não zero. */
    public double ctr() {
        return exibicoes == 0 ? 0.0 : (double) cliques / exibicoes;
    }

    /**
     * Adesão sobre clique. É o número que o negócio reconhece, e é por isso que
     * ele pesa mais que o CTR no {@code boost}: sugestão com clique alto e
     * adesão baixa é isca, e o índice aprende a repetir isca se os dois virarem
     * um número só.
     */
    public double taxaAdesao() {
        return cliques == 0 ? 0.0 : (double) adesoes / cliques;
    }

    public boolean acimaDoPiso(int piso) {
        return clientesDistintos >= piso;
    }
}
