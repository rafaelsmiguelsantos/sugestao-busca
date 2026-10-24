package dev.sugestao.busca.adaptador.saida.solr;

import dev.sugestao.busca.dominio.ContextoDeBusca;
import dev.sugestao.busca.dominio.Prefixo;
import org.apache.solr.client.solrj.request.SolrQuery;

import java.util.List;

/**
 * As camadas 1 a 3 do desenho, montadas como consulta normal do Solr.
 *
 * <p>Está separada do {@link CatalogoSolr} de propósito: montar a consulta é
 * decisão de relevância e se discute lendo o código; executá-la é rede. Assim dá
 * para testar cada {@code fq} e cada peso sem subir servidor nenhum.
 *
 * <p>A camada 4 — {@code rq={!ltr model=sugestao ...}} — não entra aqui. Ela
 * espera impressão registrada com posição; sem isso, o modelo aprenderia que a
 * primeira linha é a melhor quando ela é apenas onde a linha estava.
 */
public class ConsultaDeCandidatos {

    private final int pisoKAnonimato;
    private final List<String> tiposSugeriveis;

    public ConsultaDeCandidatos(int pisoKAnonimato, List<String> tiposSugeriveis) {
        this.pisoKAnonimato = pisoKAnonimato;
        this.tiposSugeriveis = List.copyOf(tiposSugeriveis);
    }

    public SolrQuery montar(Prefixo prefixo, ContextoDeBusca contexto, int limite) {
        var query = new SolrQuery();

        // ------------------------------------------------------------------
        // 2 · RECUPERAR — prefixo e vocabulário
        // ------------------------------------------------------------------
        // O prefixo já vem normalizado para [a-z0-9 ], então não há o que
        // escapar. O EdgeNGram foi aplicado na indexação: isto é busca de
        // termo, não varredura.
        query.setQuery(prefixo.valor());
        query.set("defType", "edismax");
        query.set("qf", "termo_prefixo^6 termo_infixo^2 sinonimo^3");

        // ------------------------------------------------------------------
        // 1 · FILTRAR — tirar o que não pode aparecer
        // ------------------------------------------------------------------
        query.addFilterQuery("tipo:(" + String.join(" OR ", tiposSugeriveis) + ")");
        query.addFilterQuery("vigencia_inicio_dt:[* TO NOW]");
        query.addFilterQuery("vigencia_fim_dt:[NOW TO *]");

        // Segmento e faixa vêm do token. São os dois unicos cortes de publico
        // que cabem aqui, e cabem exatamente porque nao dependem do perfil:
        // (prefixo, segmento, relacionamento) e uma chave de cache estavel.
        query.addFilterQuery("{!terms f=segmento_ss}" + contexto.segmento().name());
        query.addFilterQuery("{!terms f=relacionamento_ss}" + contexto.relacionamento().name());

        // O piso de k-anonimato é fq, nunca pós-filtro: um termo abaixo dele
        // não chega a pontuar, nem a ser contado. Afrouxar este número é o
        // único parâmetro do projeto que vira incidente de privacidade em vez
        // de queda de relevância.
        query.addFilterQuery("clientes_distintos_i:[" + pisoKAnonimato + " TO *]");

        // Produto contratado, elegibilidade e situação da conta seriam fq aqui
        // se o perfil chegasse antes da consulta. Ele não chega — as duas
        // leituras correm em paralelo — então esse corte acontece na Peneira,
        // sobre vinte candidatos e dois milissegundos de CPU.

        // ------------------------------------------------------------------
        // 3 · INCLINAR — comportamento entra aqui
        // ------------------------------------------------------------------
        // Adesão pesa mais que clique. Sugestão com clique alto e adesão baixa
        // é isca, e o índice aprende a repeti-la se ninguém separar os pesos.
        //
        // A recência usa NOW/HOUR, não NOW. Com NOW a chave do cache muda a
        // cada milissegundo e a camada popular, que é a parte cacheável desta
        // consulta, nunca acerta o cache. Uma hora de granularidade não muda
        // ranking nenhum e devolve o cache.
        query.set("boost", "sum("
                + "product(field(taxa_adesao_f),10),"
                + "product(field(ctr_f),3),"
                + "recip(ms(NOW/HOUR,visto_em_dt),3.16e-11,1,1))");

        // O gatilho seria um bq aqui. Está no ClassificadorDeOrigem, porque
        // depende do perfil e porque quem garante a linha do evento é a cota.

        query.setRows(limite);
        query.setFields("termo", "rotulo_s", "destino_s", "jornada_s", "tipo",
                "produto_alvo_s", "exige_elegibilidade_b", "veta_inadimplencia_b",
                "exige_conta_ativa_b", "gatilho_ss", "clientes_distintos_i", "score");

        return query;
    }
}
