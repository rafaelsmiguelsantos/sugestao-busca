package dev.sugestao.busca.adaptador.saida.solr;

import dev.sugestao.busca.aplicacao.ToleranciaAplicada;
import dev.sugestao.busca.aplicacao.porta.saida.CatalogoDeSugestoes;
import dev.sugestao.busca.dominio.Candidato;
import dev.sugestao.busca.dominio.ContextoDeBusca;
import dev.sugestao.busca.dominio.Origem;
import dev.sugestao.busca.dominio.Prefixo;
import dev.sugestao.busca.dominio.Procedencia;
import dev.sugestao.busca.dominio.Restricoes;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.common.SolrDocument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final boolean toleranciaHabilitada;

    /**
     * Quantos candidatos a consulta exata precisa trazer para a segunda
     * tentativa não acontecer.
     *
     * <p>Não é zero. Zero esperaria a lista vazia para agir, e a lista quase
     * nunca vem completamente vazia: a camada popular costuma devolver uma linha
     * de ação qualquer que casou por infixo. Era exatamente o caso de
     * {@code q=financiamento}, que devolvia uma linha e nenhuma oferta. Com piso
     * zero, a tolerância nunca teria disparado naquele caso — que é o caso que
     * ela existe para pegar.
     */
    static final int CANDIDATOS_SUFICIENTES = 3;

    public CatalogoSolr(SolrClient solr, String colecao, ConsultaDeCandidatos consulta,
                        boolean toleranciaHabilitada) {
        this.solr = solr;
        this.colecao = colecao;
        this.consulta = consulta;
        this.toleranciaHabilitada = toleranciaHabilitada;
    }

    /**
     * Uma ida ao índice no caso comum, duas quando a primeira trouxe pouco.
     *
     * <p><strong>Por que a decisão mora aqui e não no caso de uso.</strong>
     * Quantas chamadas de rede uma consulta custa é detalhe de implementação
     * desta porta. O caso de uso pede candidatos para um prefixo; se isso são
     * duas consultas ao Solr, uma consulta ou uma leitura de cache é assunto do
     * adaptador. Levar a decisão para o caso de uso o faria conhecer "fuzzy",
     * que é vocabulário de motor de busca.
     *
     * <p><strong>E por que a segunda ida cabe no orçamento.</strong> Ela só
     * acontece quando a primeira falhou — e o prazo do leque é contado do início
     * da requisição, em {@code SugerirSugestoesCasoDeUso.aguardar}, de modo que
     * a segunda consulta não ganha orçamento novo: ela cabe no que sobrou ou é
     * cancelada junto. O pior caso é a lista vazia que já era a resposta.
     *
     * <p>Os resultados da segunda <strong>vão para o fim</strong>, e os exatos
     * são preservados na frente. Nenhuma recuperação de erro de digitação passa
     * na frente de um casamento exato, mesmo que o Solr tenha dado score maior
     * para ela — o score da tolerante mede semelhança de escrita, e os dois
     * números não são comparáveis.
     */
    @Override
    public Busca candidatos(Prefixo prefixo, ContextoDeBusca contexto, int limite) {
        List<Candidato> exatos = executar(consulta.montar(prefixo, contexto, limite));

        if (!toleranciaHabilitada) {
            return new Busca(exatos, ToleranciaAplicada.DESLIGADA);
        }
        if (exatos.size() >= CANDIDATOS_SUFICIENTES) {
            return new Busca(exatos, ToleranciaAplicada.NAO_PRECISOU);
        }
        if (!consulta.toleravel(prefixo)) {
            return new Busca(exatos, ToleranciaAplicada.NAO_ELEGIVEL);
        }

        var tolerantes = executar(consulta.montarTolerante(prefixo, contexto, limite));

        // Deduplica por termo contra o que a consulta exata já trouxe. Sem isto,
        // "consignado" voltaria duas vezes quando a exata acha uma linha e a
        // tolerante acha a mesma — e a deduplicação do Misturador registraria
        // TERMO_DUPLICADO, poluindo a lista de descartes com ruído interno que
        // não diz nada sobre o perfil de ninguém.
        Set<String> jaTemos = exatos.stream().map(Candidato::termo).collect(Collectors.toSet());

        var novos = tolerantes.stream()
                .filter(c -> !jaTemos.contains(c.termo()))
                .toList();

        if (novos.isEmpty()) {
            return new Busca(exatos, ToleranciaAplicada.ACIONADA_SEM_GANHO);
        }

        var juntos = new ArrayList<Candidato>(exatos.size() + novos.size());
        juntos.addAll(exatos);
        juntos.addAll(novos);

        return new Busca(juntos, ToleranciaAplicada.ACIONADA_COM_GANHO);
    }

    private List<Candidato> executar(SolrQuery query) {
        try {
            var resposta = solr.query(colecao, query);
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
