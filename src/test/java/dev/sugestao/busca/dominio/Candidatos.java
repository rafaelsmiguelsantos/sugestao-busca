package dev.sugestao.busca.dominio;

import java.util.List;

/** Construtor de candidatos para os testes, só para o ruído não encobrir a regra. */
final class Candidatos {

    private Candidatos() {
    }

    static Candidato de(String termo, Origem origem, double relevancia) {
        return new Candidato(termo, "", "/app/" + termo, "busca", "",
                Restricoes.NENHUMA, List.of(), 1000, relevancia, origem);
    }

    static Candidato doIndice(String termo, double relevancia, int clientesDistintos) {
        return new Candidato(termo, "", "/app/" + termo, "busca", "",
                Restricoes.NENHUMA, List.of(), clientesDistintos, relevancia, Origem.POPULAR);
    }

    static Candidato produto(String termo, String produto, boolean exigeElegibilidade) {
        return comRestricoes(termo, produto, new Restricoes(exigeElegibilidade, false, false));
    }

    static Candidato comRestricoes(String termo, String produto, Restricoes restricoes) {
        return new Candidato(termo, "", "/app/" + termo, "credito", produto,
                restricoes, List.of(), 1000, 1.0, Origem.CATALOGO);
    }

    static Candidato comGatilho(String termo, String gatilho) {
        return new Candidato(termo, "", "/app/" + termo, "credito", "",
                Restricoes.NENHUMA, List.of(gatilho), 1000, 1.0, Origem.CATALOGO);
    }
}
