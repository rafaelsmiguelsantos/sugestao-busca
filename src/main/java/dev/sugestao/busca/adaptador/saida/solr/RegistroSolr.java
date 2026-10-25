package dev.sugestao.busca.adaptador.saida.solr;

import dev.sugestao.busca.aplicacao.porta.saida.RegistroDeOfertas;
import dev.sugestao.busca.dominio.Relacionamento;
import dev.sugestao.busca.dominio.Restricoes;
import dev.sugestao.busca.dominio.Segmento;
import dev.sugestao.busca.dominio.oferta.Oferta;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.SolrQuery;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.client.solrj.util.ClientUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Guarda oferta no mesmo índice que a busca lê.
 *
 * <p>O tipo gravado é {@code oferta}, e ele precisa estar em
 * {@code sugestao.tipos-sugeriveis} para a oferta aparecer. São dois controles
 * de propósito: cadastrar e exibir são decisões diferentes, e desligar a
 * exibição de todas as ofertas num incidente não deve exigir apagar o cadastro
 * de ninguém.
 *
 * <p>Escreve <strong>todos</strong> os campos que o caminho de leitura filtra,
 * inclusive os que valem zero. Campo ausente no Solr não casa com
 * {@code {!terms}} nem com faixa de data, e um documento gravado sem
 * {@code relacionamento_ss} fica invisível para a base inteira sem erro nenhum
 * em lugar nenhum — foi exatamente assim que o catálogo semeado sumiu uma vez.
 * Por isso aqui não há campo opcional: o que não tem valor recebe vazio
 * explícito.
 *
 * <p>Usa o cliente de publicação, não o de leitura. O de leitura tem 18 ms de
 * prazo porque há alguém digitando do outro lado; aqui há uma jornada esperando
 * confirmação de gravação, e {@code add} seguido de {@code commit} leva o tempo
 * que levar.
 */
public class RegistroSolr implements RegistroDeOfertas {

    /** O tipo de documento de uma oferta. Ver {@code sugestao.tipos-sugeriveis}. */
    public static final String TIPO = "oferta";

    private final SolrClient solr;
    private final String colecao;

    public RegistroSolr(SolrClient solr, String colecao) {
        this.solr = solr;
        this.colecao = colecao;
    }

    @Override
    public void gravar(Oferta oferta) {
        try {
            solr.add(colecao, paraDocumento(oferta));

            // Commit explícito, e não commitWithin. A jornada que acabou de
            // cadastrar vai conferir com GET /v1/ofertas/{id}, e muitas vão
            // conferir com uma busca. Prometer gravação e devolver 404 na
            // leitura seguinte seria o pior tipo de resposta correta.
            solr.commit(colecao);
        } catch (Exception e) {
            throw new FalhaNoIndice("gravacao da oferta " + oferta.ofertaId() + " falhou", e);
        }
    }

    @Override
    public Optional<Oferta> porId(String ofertaId) {
        var query = new SolrQuery();

        // Escapa o valor mesmo com o formato já validado pelo domínio. A
        // validação é de quem cria a oferta; esta leitura também atende
        // GET /v1/ofertas/{id}, onde o texto vem da URL e não passou por
        // Oferta.de — é o único ponto onde texto de fora chega a uma consulta.
        query.setQuery("oferta_id_s:" + ClientUtils.escapeQueryChars(ofertaId));
        query.addFilterQuery("tipo:" + TIPO);
        query.setRows(1);
        query.setFields("oferta_id_s", "termo", "sinonimo", "rotulo_s", "destino_s",
                "jornada_s", "produto_alvo_s", "exige_elegibilidade_b",
                "veta_inadimplencia_b", "exige_conta_ativa_b", "gatilho_ss",
                "segmento_ss", "relacionamento_ss", "vigencia_inicio_dt", "vigencia_fim_dt");

        try {
            var resultados = solr.query(colecao, query).getResults();
            if (resultados.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(paraOferta(resultados.getFirst()));
        } catch (Exception e) {
            throw new FalhaNoIndice("leitura da oferta " + ofertaId + " falhou", e);
        }
    }

    private static SolrInputDocument paraDocumento(Oferta oferta) {
        var doc = new SolrInputDocument();

        doc.setField("id", oferta.idDoDocumento());
        doc.setField("tipo", TIPO);
        doc.setField("oferta_id_s", oferta.ofertaId());
        doc.setField("termo", oferta.termo());
        doc.setField("sinonimo", oferta.sinonimos());
        doc.setField("jornada_s", oferta.jornada());
        doc.setField("produto_alvo_s", oferta.produtoAlvo());
        doc.setField("destino_s", oferta.destino());
        doc.setField("rotulo_s", oferta.rotulo());

        doc.setField("segmento_ss", oferta.segmentos().stream().map(Enum::name).toList());
        doc.setField("relacionamento_ss", oferta.relacionamentos().stream().map(Enum::name).toList());
        doc.setField("canal_ss", List.of("app"));
        doc.setField("gatilho_ss", oferta.gatilhos());

        doc.setField("exige_elegibilidade_b", oferta.restricoes().exigeElegibilidade());
        doc.setField("veta_inadimplencia_b", oferta.restricoes().vetaInadimplencia());
        doc.setField("exige_conta_ativa_b", oferta.restricoes().exigeContaAtiva());

        doc.setField("vigencia_inicio_dt", oferta.vigenciaInicio().toString());
        doc.setField("vigencia_fim_dt", oferta.vigenciaFim().toString());

        // Os quatro números que o cadastro não aceita, gravados em zero.
        //
        // Zero é o valor certo e não um marcador: a oferta nasce sem nenhuma
        // impressão, nenhum clique e nenhuma adesão, e é isso que o ranking deve
        // saber sobre ela. Ela compete pelo prefixo e pela jornada líder, que é
        // o sinal que ela tem, e ganha os outros três se merecer.
        //
        // clientes_distintos_i em zero não a esconde: o piso de k-anonimato vale
        // só para tipo:consulta. Ver ConsultaDeCandidatos.
        doc.setField("visto_em_dt", Instant.now().toString());
        doc.setField("freq_30d_i", 0);
        doc.setField("clientes_distintos_i", 0);
        doc.setField("ctr_f", 0.0f);
        doc.setField("taxa_adesao_f", 0.0f);

        return doc;
    }

    /**
     * Reconstrói sem revalidar, pelo construtor canônico.
     *
     * <p>{@code Oferta.de} seria a chamada natural e é a errada: ela valida, e
     * validar na leitura faz uma regra nova transformar oferta antiga em
     * {@code 500} na hora de consultá-la. A regra decide o que entra; o que já
     * entrou se lê como está. É o que permite endurecer a validação depois sem
     * quebrar o que está gravado.
     */
    private static Oferta paraOferta(SolrDocument doc) {
        var segmentos = new LinkedHashSet<Segmento>();
        for (String bruto : lista(doc, "segmento_ss")) {
            Segmento.talvez(bruto).ifPresent(segmentos::add);
        }

        var faixas = new LinkedHashSet<Relacionamento>();
        for (String bruto : lista(doc, "relacionamento_ss")) {
            Relacionamento.talvez(bruto).ifPresent(faixas::add);
        }

        return new Oferta(
                texto(doc, "oferta_id_s"),
                texto(doc, "termo"),
                lista(doc, "sinonimo"),
                texto(doc, "rotulo_s"),
                texto(doc, "destino_s"),
                texto(doc, "jornada_s"),
                texto(doc, "produto_alvo_s"),
                new Restricoes(
                        booleano(doc, "exige_elegibilidade_b"),
                        booleano(doc, "veta_inadimplencia_b"),
                        booleano(doc, "exige_conta_ativa_b")),
                lista(doc, "gatilho_ss"),
                Set.copyOf(segmentos),
                Set.copyOf(faixas),
                instante(doc, "vigencia_inicio_dt"),
                instante(doc, "vigencia_fim_dt"));
    }

    private static String texto(SolrDocument doc, String campo) {
        Object valor = doc.getFirstValue(campo);
        return valor == null ? "" : valor.toString();
    }

    private static boolean booleano(SolrDocument doc, String campo) {
        return doc.getFirstValue(campo) instanceof Boolean b && b;
    }

    private static Instant instante(SolrDocument doc, String campo) {
        Object valor = doc.getFirstValue(campo);
        if (valor instanceof Date data) {
            return data.toInstant();
        }
        if (valor == null) {
            return null;
        }
        return Instant.parse(valor.toString());
    }

    private static List<String> lista(SolrDocument doc, String campo) {
        Collection<Object> valores = doc.getFieldValues(campo);
        if (valores == null) {
            return List.of();
        }
        var textos = new ArrayList<String>(valores.size());
        for (Object valor : valores) {
            textos.add(valor.toString());
        }
        return textos;
    }
}
