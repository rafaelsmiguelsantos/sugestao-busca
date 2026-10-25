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

    /**
     * Uma oferta acionável: tem identificador e produto, vem do catálogo, e
     * nasce com zero clientes distintos — que é o estado real de uma oferta
     * recém-cadastrada.
     */
    static Candidato oferta(String termo, String ofertaId, String produto) {
        return new Candidato(ofertaId, termo, "", "/app/" + termo, "credito", produto,
                Restricoes.NENHUMA, List.of(), 0, 1.0, Origem.CATALOGO, Procedencia.CATALOGO);
    }

    static Candidato oferta(String termo, String ofertaId, String produto, double relevancia) {
        return new Candidato(ofertaId, termo, "", "/app/" + termo, "credito", produto,
                Restricoes.NENHUMA, List.of(), 0, relevancia, Origem.CATALOGO, Procedencia.CATALOGO);
    }

    /** Oferta com gatilho, para o caminho da promoção a evento. */
    static Candidato ofertaComGatilho(String termo, String ofertaId, String produto, String gatilho) {
        return new Candidato(ofertaId, termo, "", "/app/" + termo, "credito", produto,
                Restricoes.NENHUMA, List.of(gatilho), 0, 1.0, Origem.CATALOGO, Procedencia.CATALOGO);
    }

    /** Termo agregado do log: responde pelo piso de k-anonimato. */
    static Candidato doLog(String termo, int clientesDistintos, String gatilho) {
        return new Candidato("", termo, "", "/busca", "busca", "",
                Restricoes.NENHUMA, gatilho == null ? List.of() : List.of(gatilho),
                clientesDistintos, 1.0, Origem.POPULAR, Procedencia.LOG);
    }
}
