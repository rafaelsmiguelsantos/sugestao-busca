package dev.sugestao.busca.adaptador.saida.solr;

import dev.sugestao.busca.aplicacao.porta.saida.CatalogoCurado;
import dev.sugestao.busca.dominio.agregado.ItemCurado;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.common.SolrDocument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Lê do índice o que alguém curou — tudo o que não é {@code tipo:consulta}.
 *
 * <p>O corte é esse e não outro: consulta é o que veio do log, e usar o log
 * para classificar o log seria circular. Produto, ação e artigo de ajuda
 * chegaram ao índice pelo semeador, a partir de um arquivo que uma pessoa
 * editou — é a única fonte do sistema onde a relação entre palavra e jornada
 * foi decidida e não inferida.
 *
 * <p>Usa o cliente de publicação, não o de leitura: isto roda no caminho de
 * aprendizado, onde não há prazo, e são milhares de linhas de uma vez.
 */
public class CatalogoCuradoSolr implements CatalogoCurado {

    private static final int TETO = 5_000;

    private final SolrClient solr;
    private final String colecao;

    public CatalogoCuradoSolr(SolrClient solr, String colecao) {
        this.solr = solr;
        this.colecao = colecao;
    }

    @Override
    public List<ItemCurado> itens() {
        var query = new SolrQuery();
        query.setQuery("*:*");
        query.addFilterQuery("-tipo:consulta");
        query.setFields("termo", "sinonimo", "jornada_s");
        query.setRows(TETO);

        try {
            var resposta = solr.query(colecao, query);
            var itens = new ArrayList<ItemCurado>(resposta.getResults().size());
            for (SolrDocument doc : resposta.getResults()) {
                itens.add(new ItemCurado(
                        texto(doc, "termo"),
                        lista(doc, "sinonimo"),
                        texto(doc, "jornada_s")));
            }
            return itens;
        } catch (Exception e) {
            throw new FalhaNoIndice("leitura do catálogo curado falhou", e);
        }
    }

    private static String texto(SolrDocument doc, String campo) {
        Object valor = doc.getFirstValue(campo);
        return valor == null ? "" : valor.toString();
    }

    private static List<String> lista(SolrDocument doc, String campo) {
        Collection<Object> valores = doc.getFieldValues(campo);
        if (valores == null) {
            return List.of();
        }
        return valores.stream().map(Object::toString).toList();
    }
}
