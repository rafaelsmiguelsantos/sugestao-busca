package dev.sugestao.busca.adaptador.saida.solr;

import dev.sugestao.busca.aplicacao.porta.saida.CatalogoDeSugestoes;
import dev.sugestao.busca.dominio.Candidato;
import dev.sugestao.busca.dominio.ContextoDeBusca;
import dev.sugestao.busca.dominio.Origem;
import dev.sugestao.busca.dominio.Prefixo;
import dev.sugestao.busca.dominio.Procedencia;
import dev.sugestao.busca.dominio.Restricoes;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrDocument;

import java.util.Collection;
import java.util.List;

/**
 * O adaptador do índice. Executa a consulta e traduz documento em
 * {@link Candidato} — e é o único ponto do projeto que conhece nome de campo do
 * Solr. Se o esquema mudar, muda aqui e em {@code solr/schema-sugestoes.json},
 * em lugar nenhum mais.
 */
public class CatalogoSolr implements CatalogoDeSugestoes {

    private final SolrClient solr;
    private final String colecao;
    private final ConsultaDeCandidatos consulta;

    public CatalogoSolr(SolrClient solr, String colecao, ConsultaDeCandidatos consulta) {
        this.solr = solr;
        this.colecao = colecao;
        this.consulta = consulta;
    }

    @Override
    public List<Candidato> candidatos(Prefixo prefixo, ContextoDeBusca contexto, int limite) {
        try {
            var resposta = solr.query(colecao, consulta.montar(prefixo, contexto, limite));
            return resposta.getResults().stream().map(CatalogoSolr::paraCandidato).toList();
        } catch (Exception e) {
            throw new FalhaNoIndice("consulta ao índice de sugestões falhou", e);
        }
    }

    private static Candidato paraCandidato(SolrDocument doc) {
        boolean doLog = "consulta".equals(texto(doc, "tipo"));

        // A consulta veio do log de busca de muita gente: é a camada popular, e
        // é ela que o piso de k-anonimato protege. O resto foi curado por
        // alguém, ou cadastrado por uma jornada, e tem vigência e elegibilidade
        // declaradas: é catálogo.
        //
        // É o único ponto do projeto onde a procedência é decidida a partir do
        // dado, e por isso o único onde ela pode ser decidida errado. Tudo
        // depois daqui a propaga; nada depois daqui a recalcula.
        Origem origem = doLog ? Origem.POPULAR : Origem.CATALOGO;
        Procedencia procedencia = doLog ? Procedencia.LOG : Procedencia.CATALOGO;

        return new Candidato(
                texto(doc, "oferta_id_s"),
                texto(doc, "termo"),
                texto(doc, "rotulo_s"),
                texto(doc, "destino_s"),
                texto(doc, "jornada_s"),
                texto(doc, "produto_alvo_s"),
                new Restricoes(
                        booleano(doc, "exige_elegibilidade_b"),
                        booleano(doc, "veta_inadimplencia_b"),
                        booleano(doc, "exige_conta_ativa_b")),
                lista(doc, "gatilho_ss"),
                inteiro(doc, "clientes_distintos_i"),
                decimal(doc, "score"),
                origem,
                procedencia);
    }

    private static String texto(SolrDocument doc, String campo) {
        Object valor = doc.getFirstValue(campo);
        return valor == null ? "" : valor.toString();
    }

    private static boolean booleano(SolrDocument doc, String campo) {
        return doc.getFirstValue(campo) instanceof Boolean b && b;
    }

    private static int inteiro(SolrDocument doc, String campo) {
        return doc.getFirstValue(campo) instanceof Number n ? n.intValue() : 0;
    }

    private static double decimal(SolrDocument doc, String campo) {
        return doc.getFirstValue(campo) instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static List<String> lista(SolrDocument doc, String campo) {
        Collection<Object> valores = doc.getFieldValues(campo);
        if (valores == null) {
            return List.of();
        }
        return valores.stream().map(Object::toString).toList();
    }
}
