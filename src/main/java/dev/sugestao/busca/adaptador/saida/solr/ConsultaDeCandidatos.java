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

    /**
     * Abaixo deste comprimento a tolerância não é tentada.
     *
     * <p>Não é economia, é precisão. Com quatro letras, distância de edição 1
     * alcança um vizinho enorme: {@code card} casa {@code cart}, {@code cardi},
     * {@code carro}, {@code cara}. A lista voltaria cheia e errada, e lista
     * errada com confiança é pior que lista vazia — em banco, oferecer consórcio
     * a quem digitou "consignado" torto não é recuperação simpática, é produto
     * errado.
     */
    static final int MINIMO_PARA_TOLERAR = 5;

    /** De onde em diante vale distância 2 em vez de 1. */
    static final int MINIMO_PARA_DISTANCIA_2 = 8;

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
        // Os filtros estão em `aplicarFiltros` porque a consulta tolerante usa
        // exatamente os mesmos. Ver o javadoc de montarTolerante: filtro que
        // valha só para a consulta exata transforma a tolerante na forma de
        // contorná-lo, e o piso de k-anonimato é um dos filtros.
        //
        // Entre eles, dois merecem a explicação que estava aqui:
        //
        // Segmento e faixa vêm do token. São os dois únicos cortes de público
        // que cabem no índice, e cabem exatamente porque não dependem do
        // perfil: (prefixo, segmento, relacionamento) é chave de cache estável.
        //
        // O piso de k-anonimato é fq, nunca pós-filtro: um termo abaixo dele não
        // chega a pontuar, nem a ser contado. Afrouxar esse número é o único
        // parâmetro do projeto que vira incidente de privacidade em vez de queda
        // de relevância. E vale só para tipo:consulta, que é o que veio do log de
        // busca de gente — a forma disjuntiva, lida em voz alta, é a regra: "ou
        // não é termo do log, ou limpa o piso".
        aplicarFiltros(query, contexto);

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
        query.setFields(CAMPOS);

        return query;
    }

    /**
     * A segunda tentativa: a mesma consulta, tolerando erro de digitação.
     *
     * <p><strong>É consulta separada, e não uma cláusula a mais na primeira.</strong>
     * Essa é a decisão que faz a tolerância custar zero no caminho quente. Com
     * {@code q = "emprestmo OR emprestmo~1"} no edismax, o fuzzy seria aplicado a
     * todos os campos do {@code qf} — inclusive aos dois EdgeNGram, onde cada
     * palavra já está expandida em dezenas de gramas e a enumeração de termos
     * difusos multiplica por isso. Toda busca pagaria por um recurso que só
     * importa quando a busca falhou.
     *
     * <p>Assim, quem decide se vale a segunda ida é o {@link CatalogoSolr}, e ele
     * só decide isso quando a primeira trouxe pouco — exatamente quando não há
     * nada a perder, porque a alternativa era a lista vazia.
     *
     * <p><strong>Campo próprio, sem EdgeNGram.</strong> Vai contra
     * {@code termo_tolerante}, que é palavra inteira. Fuzzy sobre grama de
     * prefixo compara pedaço com pedaço e acha semelhança onde não há: o grama
     * {@code emp} está a distância 1 de {@code amp}, {@code emb} e {@code imp},
     * que são começos de palavras sem relação nenhuma.
     *
     * <p><strong>Os filtros são os mesmos.</strong> Repetir segmento, faixa,
     * vigência e piso de k-anonimato não é duplicação a ser removida: é a
     * garantia de que a segunda tentativa não é uma porta lateral para o que a
     * primeira filtrou. Um fuzzy sem o {@code fq} do piso devolveria termo de
     * pouca gente com erro de digitação por cima — vazamento com disfarce.
     */
    public SolrQuery montarTolerante(Prefixo prefixo, ContextoDeBusca contexto, int limite) {
        var query = new SolrQuery();

        query.setQuery(expressaoTolerante(prefixo.valor()));

        // defType lucene, e não edismax: a expressão já nomeia o campo e a
        // distância de cada termo, e o edismax reescreveria isso contra o qf.
        query.set("defType", "lucene");

        aplicarFiltros(query, contexto);

        // Sem o `boost` multiplicativo da consulta exata. Aqui o score mede
        // semelhança de escrita, não interesse: multiplicar por CTR faria uma
        // recuperação de erro de digitação ser ordenada por popularidade da
        // população, e o que a pessoa quer é a palavra que ela tentou escrever.
        query.setRows(limite);
        query.setFields(CAMPOS);

        return query;
    }

    /**
     * Se vale tentar tolerar este prefixo.
     *
     * <p>Basta uma palavra com tamanho suficiente: em "financiemento imovel" o
     * erro está na primeira, e exigir que todas passassem o mínimo descartaria a
     * frase por causa de "imovel".
     */
    public boolean toleravel(Prefixo prefixo) {
        for (String palavra : prefixo.valor().split("\\s+")) {
            if (palavra.length() >= MINIMO_PARA_TOLERAR) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code termo_tolerante:(financiemento~2 imovel~1)}.
     *
     * <p>A distância cresce com o tamanho da palavra porque é assim que o erro
     * se comporta: uma letra errada em cinco é 20% do texto, uma letra errada em
     * doze é 8%. Distância fixa 2 seria frouxa demais em palavra curta e apertada
     * demais em palavra longa.
     *
     * <p>Palavra abaixo do mínimo entra <em>exata</em>, sem {@code ~}. Ela não é
     * descartada — "imovel" ainda precisa casar — mas também não ganha licença
     * para variar.
     */
    private static String expressaoTolerante(String prefixoNormalizado) {
        var termos = new StringBuilder();

        for (String palavra : prefixoNormalizado.split("\\s+")) {
            if (palavra.isEmpty()) {
                continue;
            }
            if (!termos.isEmpty()) {
                termos.append(' ');
            }
            termos.append(palavra);

            if (palavra.length() >= MINIMO_PARA_DISTANCIA_2) {
                termos.append("~2");
            } else if (palavra.length() >= MINIMO_PARA_TOLERAR) {
                termos.append("~1");
            }
        }

        // O prefixo já vem normalizado para [a-z0-9 ] por Prefixo/Normalizacao,
        // então não há metacaractere de consulta para escapar aqui. É a mesma
        // garantia de que a consulta exata depende.
        return "termo_tolerante:(" + termos + ")";
    }

    /**
     * Os filtros que valem para qualquer tentativa.
     *
     * <p>Extraídos para que a consulta tolerante não possa divergir da exata por
     * esquecimento. Se um filtro novo entrar só na exata, a tolerante vira a
     * forma de contorná-lo.
     */
    private void aplicarFiltros(SolrQuery query, ContextoDeBusca contexto) {
        query.addFilterQuery("tipo:(" + String.join(" OR ", tiposSugeriveis) + ")");
        query.addFilterQuery("vigencia_inicio_dt:[* TO NOW]");
        query.addFilterQuery("vigencia_fim_dt:[NOW TO *]");
        query.addFilterQuery("{!terms f=segmento_ss}" + contexto.segmento().name());
        query.addFilterQuery("{!terms f=relacionamento_ss}" + contexto.relacionamento().name());
        query.addFilterQuery(
                "(*:* -tipo:consulta) OR clientes_distintos_i:[" + pisoKAnonimato + " TO *]");
    }

    private static final String[] CAMPOS = {
            "oferta_id_s", "termo", "rotulo_s", "destino_s", "jornada_s", "tipo",
            "produto_alvo_s", "exige_elegibilidade_b", "veta_inadimplencia_b",
            "exige_conta_ativa_b", "gatilho_ss", "clientes_distintos_i", "score"
    };
}
