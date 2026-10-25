package dev.sugestao.busca.aplicacao;

import dev.sugestao.busca.dominio.Cotas;
import dev.sugestao.busca.dominio.PesosDeRanking;

/**
 * Tudo o que o desenho chama de "parâmetro", reunido num valor só. Fica aqui, e
 * não espalhado em constantes, porque cada um destes números é uma decisão que
 * alguém vai querer discutir — e discutir número escondido em código é discutir
 * no escuro.
 */
public record ParametrosDeSugestao(
        int prefixoMinimo,
        int candidatosDoIndice,
        Cotas cotas,
        PesosDeRanking pesos,
        Prazos prazos) {

    /** Sem pesos declarados, usa os padrão. Conveniência para teste. */
    public ParametrosDeSugestao(int prefixoMinimo, int candidatosDoIndice, Cotas cotas, Prazos prazos) {
        this(prefixoMinimo, candidatosDoIndice, cotas, PesosDeRanking.padrao(), prazos);
    }

    public ParametrosDeSugestao {
        if (prefixoMinimo < 1) {
            throw new IllegalArgumentException("prefixo mínimo precisa ser ao menos 1");
        }
        if (candidatosDoIndice < cotas.limite()) {
            throw new IllegalArgumentException(
                    "pedir ao índice menos candidatos do que cabe na lista deixa a cota sem o que escolher");
        }
    }
}
