package dev.sugestao.busca.adaptador.saida.solr;

import dev.sugestao.busca.aplicacao.porta.saida.PublicadorDeVocabulario;
import dev.sugestao.busca.dominio.Normalizacao;
import dev.sugestao.busca.dominio.agregado.TermoAgregado;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Escreve no índice os termos que sobreviveram ao piso.
 *
 * <p>O identificador do documento é derivado do termo normalizado, com o mesmo
 * prefixo {@code log-} que o semeador usa. Isso faz a publicação ser
 * idempotente: a mesma rodada rodada duas vezes atualiza os mesmos documentos
 * em vez de duplicá-los, e uma rodada nova sobrescreve os contadores da
 * anterior em vez de somá-los — que é o comportamento certo para uma janela
 * deslizante, onde o número é um retrato e não um acumulado.
 *
 * <p>Não decide o que pode ser publicado. Recebe a lista já peneirada pelo caso
 * de uso, de propósito: um adaptador que também aplicasse o piso de
 * k-anonimato seria um adaptador que pode ser trocado junto com a regra.
 */
public class PublicadorSolr implements PublicadorDeVocabulario {

    private static final List<String> TODAS_AS_FAIXAS =
            List.of("VAREJO", "UNICLASS", "PERSONNALITE", "PRIVATE");

    private final SolrClient solr;
    private final String colecao;

    public PublicadorSolr(SolrClient solr, String colecao) {
        this.solr = solr;
        this.colecao = colecao;
    }

    @Override
    public int publicar(List<TermoAgregado> termos) {
        var documentos = new ArrayList<SolrInputDocument>(termos.size());
        Instant agora = Instant.now();

        for (TermoAgregado termo : termos) {
            documentos.add(paraDocumento(termo, agora));
        }

        try {
            solr.add(colecao, documentos);
            solr.commit(colecao);
            return documentos.size();
        } catch (Exception e) {
            throw new FalhaNoIndice("publicação do vocabulário falhou", e);
        }
    }

    private static SolrInputDocument paraDocumento(TermoAgregado termo, Instant agora) {
        String chave = Normalizacao.aplicar(termo.termo());
        var doc = new SolrInputDocument();

        doc.setField("id", "log-" + chave.replace(' ', '-'));
        doc.setField("termo", chave);

        // Termo vindo do log é consulta, nunca catálogo: ele não tem vigência
        // declarada, não empurra produto e não exige elegibilidade de nada.
        // Quem curou catálogo foi uma pessoa; isto aqui foi digitado.
        doc.setField("tipo", "consulta");

        // A jornada foi inferida do catálogo curado, não declarada por quem
        // digitou. Ela serve ao ranking — jornada líder e afinidade do cliente
        // — e nunca às restrições: "contratar emprestimo" herda a jornada de
        // crédito, mas não herda o veto de inadimplência do produto. Restrição
        // é decisão de quem cura, e ninguém curou este termo.
        doc.setField("jornada_s", termo.jornada());
        doc.setField("produto_alvo_s", "");
        doc.setField("rotulo_s", "");
        doc.setField("destino_s", "/busca?q=" + chave.replace(' ', '+'));

        doc.setField("segmento_ss", List.copyOf(termo.segmentos()));
        doc.setField("relacionamento_ss", TODAS_AS_FAIXAS);
        doc.setField("canal_ss", List.of("app"));
        doc.setField("gatilho_ss", List.of());

        doc.setField("exige_elegibilidade_b", false);
        doc.setField("veta_inadimplencia_b", false);
        doc.setField("exige_conta_ativa_b", false);

        doc.setField("vigencia_inicio_dt", agora.minus(1, ChronoUnit.DAYS).toString());
        doc.setField("vigencia_fim_dt", agora.plus(365, ChronoUnit.DAYS).toString());
        doc.setField("visto_em_dt", termo.vistoEm().toString());

        doc.setField("freq_30d_i", termo.frequencia());
        doc.setField("clientes_distintos_i", termo.clientesDistintos());
        doc.setField("ctr_f", termo.ctr());
        doc.setField("taxa_adesao_f", termo.taxaAdesao());

        return doc;
    }
}
