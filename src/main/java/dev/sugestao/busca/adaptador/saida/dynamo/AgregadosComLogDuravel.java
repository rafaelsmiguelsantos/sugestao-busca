package dev.sugestao.busca.adaptador.saida.dynamo;

import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDeAgregados;
import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;
import dev.sugestao.busca.dominio.agregado.PesosDeAfinidade;
import dev.sugestao.busca.dominio.agregado.PesosDeFadiga;
import dev.sugestao.busca.dominio.agregado.TermoAgregado;
import dev.sugestao.busca.dominio.Segmento;
import dev.sugestao.busca.dominio.evento.EventoDeBusca;
import dev.sugestao.busca.dominio.evento.TipoDeEvento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorador que grava o <strong>log durável de eventos</strong> enquanto delega a
 * agregação para quem já sabe fazê-la.
 *
 * <h2>Por que isto é a peça mais importante da persistência, e não o perfil</h2>
 *
 * <p>O perfil é uma <em>view materializada</em>: ele é montado pela rodada de 15 s
 * a partir dos eventos. Persistir só o perfil e perder os eventos é persistir um
 * cache — sobrevive ao restart, a demonstração fica boa, e continua sem haver como
 * recalcular com pesos diferentes, treinar modelo nenhum, ou explicar como aquela
 * afinidade virou 0,47.
 *
 * <p>O log é a fonte. Com ele, o perfil é reconstruível a qualquer momento e a
 * frase <em>"existe um caminho de aprendizado que funciona e não existe dado
 * acumulado para treinar nada"</em> deixa de ser verdadeira.
 *
 * <h2>Por que decorador, e não uma porta nova</h2>
 *
 * <p>Uma porta {@code RegistroDeEventos} seria o desenho "correto" de livro, e
 * custaria uma interface na aplicação, um parâmetro novo no caso de uso de
 * aprendizado e uma implementação nula para quem não usa. O decorador não custa
 * nada disso: {@code acumular} é exatamente o ponto onde os eventos crus chegam, e
 * quem chama continua vendo {@link RepositorioDeAgregados}.
 *
 * <p>É o mesmo movimento do disjuntor, do limite de taxa, da medição e do cache —
 * o quinto, e o que mais paga a arquitetura: <strong>o domínio não mudou uma
 * linha</strong> para ganhar durabilidade.
 *
 * <h2>O que ele deliberadamente não faz</h2>
 *
 * <p>Não lê. A agregação continua acontecendo na memória, sobre a janela que o
 * adaptador decorado retém. Fazer a janela ser lida do DynamoDB a cada rodada
 * trocaria um problema declarado — <em>"a agregação recomputa a janela inteira"</em> —
 * por ele mesmo acrescido de latência de rede. A reconstrução a partir do log é
 * uma operação de <em>backfill</em>, não o caminho de toda rodada.
 */
public class AgregadosComLogDuravel implements RepositorioDeAgregados {

    private static final Logger registro = LoggerFactory.getLogger(AgregadosComLogDuravel.class);

    /** Teto do {@code BatchWriteItem} do DynamoDB. Não é escolha nossa. */
    private static final int MAXIMO_POR_LOTE = 25;

    /**
     * Prazo de inicialização. A restauração varre a tabela uma vez, no startup, e
     * ninguém está digitando enquanto ela roda — ver
     * {@code ConfiguracaoDeSugestao.cliente} para as três camadas de prazo do SDK
     * e por que esta sobreposição é necessária.
     */
    private static final AwsRequestOverrideConfiguration SETUP =
            AwsRequestOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofSeconds(30))
                    .apiCallAttemptTimeout(Duration.ofSeconds(30))
                    .build();

    private final RepositorioDeAgregados delegado;
    private final DynamoDbClient dynamo;
    private final String tabela;
    private final Duration retencao;

    /**
     * Desempate da chave de ordenação.
     *
     * <p>{@code ocorridoEm} é atribuído pelo servidor e dois eventos do mesmo
     * cliente podem cair no mesmo instante — com a resolução de {@code Instant},
     * isso acontece. Sem desempate, o segundo sobrescreveria o primeiro
     * <em>silenciosamente</em>, e o sintoma seria um log com menos eventos do que
     * a fila consumiu, sem erro em lugar nenhum.
     */
    private final AtomicLong sequencia = new AtomicLong();

    public AgregadosComLogDuravel(
            RepositorioDeAgregados delegado,
            DynamoDbClient dynamo,
            String tabela,
            Duration retencao) {

        this.delegado = delegado;
        this.dynamo = dynamo;
        this.tabela = tabela;
        this.retencao = retencao;
    }

    /**
     * Delega primeiro, grava depois — e a ordem é a decisão.
     *
     * <p>A agregação em memória é o que alimenta a busca; o log é o que alimenta o
     * futuro. Se a gravação falhar, a rodada desta vez ainda funciona e a busca
     * continua melhorando. Gravar primeiro faria uma indisponibilidade do DynamoDB
     * parar o aprendizado — trocando degradação por queda, na direção errada.
     *
     * <p>A consequência honesta: numa falha de escrita <strong>perdem-se eventos do
     * log</strong>, enquanto o perfil daquela rodada continua correto. É aceitável
     * aqui porque a fila já tem teto e já descarta por lotação — o projeto não
     * promete entrega exatamente-uma-vez em nenhum ponto deste caminho.
     */
    @Override
    public void acumular(List<EventoDeBusca> novos) {
        delegado.acumular(novos);

        if (novos.isEmpty()) {
            return;
        }
        try {
            gravar(novos);
        } catch (RuntimeException e) {
            registro.warn("log duravel nao gravou {} eventos; a agregacao desta rodada seguiu",
                    novos.size(), e);
        }
    }

    private void gravar(List<EventoDeBusca> eventos) {
        var expiraEm = Instant.now().plus(retencao).getEpochSecond();

        for (int inicio = 0; inicio < eventos.size(); inicio += MAXIMO_POR_LOTE) {
            var fim = Math.min(inicio + MAXIMO_POR_LOTE, eventos.size());
            var lote = new ArrayList<WriteRequest>(fim - inicio);

            for (var evento : eventos.subList(inicio, fim)) {
                lote.add(WriteRequest.builder()
                        .putRequest(PutRequest.builder().item(comoItem(evento, expiraEm)).build())
                        .build());
            }

            var resposta = dynamo.batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(Map.of(tabela, lote))
                    .build());

            // O BatchWriteItem pode devolver itens NÃO gravados sem lançar nada —
            // é o jeito do DynamoDB dizer "fui estrangulado". Ignorar isso é o
            // defeito clássico deste método: a chamada "deu certo" e os dados não
            // estão lá.
            if (resposta.hasUnprocessedItems() && !resposta.unprocessedItems().isEmpty()) {
                int naoGravados = resposta.unprocessedItems()
                        .getOrDefault(tabela, List.of()).size();
                registro.warn("{} eventos devolvidos como nao processados pelo DynamoDB",
                        naoGravados);
            }
        }
    }

    /**
     * O item de evento.
     *
     * <p>Carrega {@code versaoDosPesos} e {@code versaoDoCorpus} porque é isso que
     * torna uma decisão de ranking <em>reproduzível</em> — e reprodutibilidade é o
     * que auditoria quer, não um score gravado. Um score guardado é um número que
     * ninguém pode verificar depois; com o prefixo, o perfil, a versão dos pesos e
     * a versão do índice, recalcula-se e confere-se.
     *
     * <p>Os dois campos ficam como atributo do evento e não da tabela porque eles
     * mudam com o tempo: o evento tem de dizer sob quais regras ele foi produzido,
     * não sob quais regras alguém o leria hoje.
     */
    private Map<String, AttributeValue> comoItem(EventoDeBusca evento, long expiraEm) {
        var item = new LinkedHashMap<String, AttributeValue>();

        item.put(MapeamentoDePerfil.PK, MapeamentoDePerfil.chaveDeParticao(evento.clienteId()));
        item.put(MapeamentoDePerfil.SK, MapeamentoDePerfil.texto(
                evento.ocorridoEm().toString() + "#" + sequencia.incrementAndGet()));

        item.put("tipo", MapeamentoDePerfil.texto(evento.tipo().name()));
        item.put("ocorridoEm", MapeamentoDePerfil.texto(evento.ocorridoEm().toString()));
        item.put("posicao", MapeamentoDePerfil.numero(evento.posicao()));
        item.put("listaDegradada", AttributeValue.fromBool(evento.listaDegradada()));
        item.put("expiraEm", MapeamentoDePerfil.numero(expiraEm));

        // Texto vazio é valor válido no DynamoDB desde 2020, mas atributo ausente
        // custa menos e lê igual. Gravar "" seria pagar por nada em cada evento.
        if (evento.segmento() != null) {
            item.put("segmento", MapeamentoDePerfil.texto(evento.segmento().name()));
        }
        if (!evento.termo().isBlank()) {
            item.put("termo", MapeamentoDePerfil.texto(evento.termo()));
        }
        if (!evento.produto().isBlank()) {
            item.put("produto", MapeamentoDePerfil.texto(evento.produto()));
        }
        if (evento.jornada() != null && !evento.jornada().isBlank()) {
            item.put("jornada", MapeamentoDePerfil.texto(evento.jornada()));
        }
        if (!evento.termosExibidos().isEmpty()) {
            // A lista inteira que a pessoa viu, na ordem. É este campo que
            // responde "por que esta oferta foi mostrada a esta pessoa" — a
            // ordem é o fato, o score é detalhe.
            item.put("termosExibidos", MapeamentoDePerfil.listaDeTextos(evento.termosExibidos()));
        }

        return item;
    }

    /**
     * Relê o log e devolve a janela ao agregador — <strong>sem</strong> regravá-la.
     *
     * <p>Isto é o que faz a persistência entregar o que ela promete, e a ausência
     * dele produziu um defeito que valeu a medição:
     *
     * <pre>
     * antes do restart   CDB 3.217298   LCI 2.274373   (aprendido)
     * depois do restart  CDB 3.082429   LCI 2.179031   (linha de base)
     * </pre>
     *
     * <p>O perfil <em>estava</em> gravado no DynamoDB. Só que o perfil é uma view
     * materializada: ao subir, a rodada de agregação recomputou-o a partir da
     * janela de eventos em memória — que voltou contendo apenas os eventos de
     * demonstração — e sobrescreveu o aprendizado com menos informação do que a
     * view já tinha.
     *
     * <p>É a demonstração empírica de que <strong>persistir a view sem persistir a
     * fonte é persistir um cache</strong>. O conserto não é impedir a agregação de
     * sobrescrever: é devolver a ela a fonte inteira.
     *
     * <p>Acumula no <em>delegado</em>, nunca em {@code this}: passar pelo decorador
     * regravaria cada evento restaurado, e a cada restart o log dobraria de tamanho.
     *
     * <h3>O Scan é dívida declarada, e o desenho da tabela é a causa</h3>
     *
     * <p>A chave de partição é {@code CLIENTE#<id>}, que responde bem à pergunta do
     * caminho de leitura — <em>"os eventos deste cliente"</em>. Ela não responde à
     * pergunta desta restauração: <em>"os eventos de todos os clientes na última
     * janela"</em>. Para essa, só resta varrer.
     *
     * <p>Funciona num log de demonstração e <strong>não funciona em produção</strong>.
     * As duas saídas certas, em ordem de preferência: a agregação deixar de ler
     * tabela e passar a consumir um fluxo (Kinesis ou MSK), que é o desenho
     * declarado; ou um índice secundário com chave de tempo em cestos
     * ({@code DIA#2026-10-28}), se ela tiver de continuar lendo tabela.
     */
    public int restaurarDoLog(Duration janela) {
        var corte = Instant.now().minus(janela);
        var restaurados = new ArrayList<EventoDeBusca>();
        Map<String, AttributeValue> ultimaChave = null;

        try {
            do {
                var pedido = ScanRequest.builder()
                        .tableName(tabela)
                        .filterExpression("#oc >= :corte")
                        .expressionAttributeNames(Map.of("#oc", "ocorridoEm"))
                        .expressionAttributeValues(Map.of(
                                ":corte", MapeamentoDePerfil.texto(corte.toString())))
                        .exclusiveStartKey(ultimaChave)
                        .overrideConfiguration(SETUP)
                        .build();

                var resposta = dynamo.scan(pedido);
                for (var item : resposta.items()) {
                    var evento = deItem(item);
                    if (evento != null) {
                        restaurados.add(evento);
                    }
                }
                ultimaChave = resposta.hasLastEvaluatedKey() && !resposta.lastEvaluatedKey().isEmpty()
                        ? resposta.lastEvaluatedKey()
                        : null;

            } while (ultimaChave != null);

        } catch (RuntimeException e) {
            registro.warn("log duravel nao pôde ser relido; a aplicacao sobe com a janela vazia", e);
            return 0;
        }

        if (restaurados.isEmpty()) {
            registro.info("log duravel vazio: nada a restaurar");
            return 0;
        }

        // Ordem cronológica. O Scan não garante ordem, e a agregação calcula
        // decaimento por idade de evento — fora de ordem, o resultado é o mesmo,
        // mas o histórico (que é lista ordenada) sairia embaralhado.
        restaurados.sort(java.util.Comparator.comparing(EventoDeBusca::ocorridoEm));

        delegado.acumular(restaurados);
        registro.info("{} eventos restaurados do log duravel (janela de {})",
                restaurados.size(), janela);
        return restaurados.size();
    }

    /**
     * O inverso de {@link #comoItem}.
     *
     * <p>Item que não dá para reconstruir é <strong>descartado em silêncio</strong>,
     * e isso é deliberado: um evento malformado no log não deve impedir a aplicação
     * de subir. O caminho alternativo de um evento perdido é uma afinidade
     * ligeiramente diferente; o de uma exceção aqui é o serviço inteiro fora do ar.
     *
     * <p>O {@code clienteId} volta do {@code pk}, removendo o prefixo
     * {@code CLIENTE#} — que é por isso que ele existe com um separador que não
     * aparece em identificador de cliente.
     */
    private static EventoDeBusca deItem(Map<String, AttributeValue> item) {
        try {
            var pk = item.get(MapeamentoDePerfil.PK);
            var tipo = item.get("tipo");
            var ocorrido = item.get("ocorridoEm");
            if (pk == null || pk.s() == null || tipo == null || ocorrido == null) {
                return null;
            }

            var clienteId = pk.s().startsWith("CLIENTE#") ? pk.s().substring(8) : pk.s();

            return new EventoDeBusca(
                    TipoDeEvento.valueOf(tipo.s()),
                    clienteId,
                    texto(item, "segmento") == null ? null : Segmento.valueOf(texto(item, "segmento")),
                    texto(item, "termo"),
                    lista(item, "termosExibidos"),
                    item.get("posicao") == null ? 0 : Integer.parseInt(item.get("posicao").n()),
                    texto(item, "produto"),
                    texto(item, "jornada"),
                    item.get("listaDegradada") != null
                            && Boolean.TRUE.equals(item.get("listaDegradada").bool()),
                    Instant.parse(ocorrido.s()));

        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String texto(Map<String, AttributeValue> item, String campo) {
        var v = item.get(campo);
        return v == null ? null : v.s();
    }

    private static List<String> lista(Map<String, AttributeValue> item, String campo) {
        var v = item.get(campo);
        if (v == null || v.l() == null) {
            return List.of();
        }
        var lista = new ArrayList<String>(v.l().size());
        for (var e : v.l()) {
            if (e.s() != null) {
                lista.add(e.s());
            }
        }
        return lista;
    }

    // ------------------------------------------------------------- delegações

    @Override
    public List<TermoAgregado> termos(Duration janela) {
        return delegado.termos(janela);
    }

    @Override
    public List<ComportamentoDoCliente> comportamentos(
            Duration janelaDoHistorico, int tamanhoDoHistorico,
            PesosDeAfinidade pesos, PesosDeFadiga fadiga) {

        return delegado.comportamentos(janelaDoHistorico, tamanhoDoHistorico, pesos, fadiga);
    }

    @Override
    public int eventosRetidos() {
        return delegado.eventosRetidos();
    }

    @Override
    public int perfisNoTetoDeFadiga() {
        return delegado.perfisNoTetoDeFadiga();
    }
}
