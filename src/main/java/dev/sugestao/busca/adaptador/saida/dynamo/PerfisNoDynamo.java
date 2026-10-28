package dev.sugestao.busca.adaptador.saida.dynamo;

import dev.sugestao.busca.aplicacao.porta.saida.EscritorDePerfil;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.sugestao.busca.adaptador.saida.dynamo.MapeamentoDePerfil.SK_CADASTRO;
import static dev.sugestao.busca.adaptador.saida.dynamo.MapeamentoDePerfil.SK_COMPORTAMENTO;

/**
 * O registro de perfil no DynamoDB — as duas portas, uma tabela, dois itens por
 * cliente.
 *
 * <p>Substitui {@code PerfisEmMemoria} sem que o domínio saiba. É o quinto
 * recurso a entrar por decoração ou troca de adaptador, depois do disjuntor, do
 * limite de taxa, da medição e do cache — e o primeiro em que a <em>semântica</em>
 * muda de verdade, o que é o assunto do javadoc de {@link #porCliente}.
 *
 * <h2>Por que atende as duas portas, como o adaptador de memória</h2>
 *
 * <p>{@link RepositorioDePerfil} é lido dentro dos 30 ms; {@link EscritorDePerfil}
 * roda na rodada de agregação e ninguém o espera. Um objeto só atendendo as duas
 * não desfaz a separação: o caso de uso de sugestão recebe apenas a porta de
 * leitura e, por isso, <strong>não compila</strong> se tentar escrever.
 *
 * @see MapeamentoDePerfil para o desenho dos dois itens e o motivo dele
 */
public class PerfisNoDynamo implements RepositorioDePerfil, EscritorDePerfil {

    private static final Logger registro = LoggerFactory.getLogger(PerfisNoDynamo.class);

    /**
     * Prazo de inicialização, não de requisição. Ver
     * {@code TabelasDoDynamo.PRAZO_DE_SETUP} para o motivo — resumido: um número
     * de prazo pertence a uma carga de trabalho, e a semeadura dos fixtures não é
     * a carga que os 8 ms protegem.
     */
    private static final AwsRequestOverrideConfiguration SETUP =
            AwsRequestOverrideConfiguration.builder()
                    .apiCallTimeout(java.time.Duration.ofSeconds(20))
                    .apiCallAttemptTimeout(java.time.Duration.ofSeconds(20))
                    .build();

    private final DynamoDbClient dynamo;
    private final String tabela;
    private final boolean leituraConsistente;

    public PerfisNoDynamo(DynamoDbClient dynamo, String tabela, boolean leituraConsistente) {
        this.dynamo = dynamo;
        this.tabela = tabela;
        this.leituraConsistente = leituraConsistente;
    }

    /**
     * Uma {@code Query} na partição do cliente, que devolve os dois itens juntos.
     *
     * <p><strong>Falha propagando é o comportamento certo aqui, e não descuido.</strong>
     * O caso de uso envolve esta chamada em {@code aguardar(...)}, que trata prazo
     * vencido e falha de dependência do mesmo jeito: a lista sai sem
     * personalização e marcada {@code degradada}. Capturar aqui e devolver
     * {@link Perfil#NEUTRO} produziria a mesma lista <em>sem</em> a marca — e quem
     * registra a impressão perderia a informação de que aquela lista não deve
     * contar como evidência de preferência.
     *
     * <h3>Leitura consistente, e o que o DynamoDB Local esconde</h3>
     *
     * <p>{@code consistentRead} é configurável e vem <strong>ligado</strong>. A
     * leitura eventualmente consistente é metade do custo em RCU e é o que se usaria
     * em produção para um typeahead — mas ela reintroduz, agora na infraestrutura, o
     * mesmo <em>read-after-write</em> que obrigou o cache de perfil a nascer
     * desligado: a rodada de aprendizado grava e a busca seguinte pode não ver.
     *
     * <p>E aqui vem o que importa declarar: <strong>o DynamoDB Local não reproduz
     * esse problema.</strong> Ele é um processo só, sem replicação, e responde de
     * forma efetivamente consistente mesmo quando pedimos o contrário. Então
     * {@code verificar-comportamento.ps1} passando aqui <em>não prova</em> que
     * passaria contra o DynamoDB de verdade com leitura eventual. É limitação do
     * simulador, está declarada, e é o motivo de o default ser o seguro em vez do
     * barato.
     */
    @Override
    public Perfil porCliente(String clienteId) {
        var resposta = dynamo.query(QueryRequest.builder()
                .tableName(tabela)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", MapeamentoDePerfil.PK))
                .expressionAttributeValues(Map.of(
                        ":pk", MapeamentoDePerfil.chaveDeParticao(clienteId)))
                .consistentRead(leituraConsistente)
                .build());

        return MapeamentoDePerfil.perfilDeItens(resposta.items());
    }

    /**
     * A rodada de aprendizado, gravando só o que é dela.
     *
     * <p>Dois {@code UpdateItem} por cliente, nunca um {@code PutItem} do perfil
     * inteiro. O motivo está em {@link MapeamentoDePerfil}: um {@code PutItem}
     * apagaria a inadimplência que outro produtor escreveu, e o sintoma seria
     * crédito novo voltando a ser oferecido a quem está em atraso.
     */
    @Override
    public void atualizar(List<ComportamentoDoCliente> comportamentos) {
        for (var comportamento : comportamentos) {
            try {
                gravarComportamento(comportamento);
                semearGatilhos(comportamento);
            } catch (RuntimeException e) {
                // Um cliente que falha não derruba a rodada inteira. A alternativa
                // seria perder o aprendizado de todos os outros do lote por causa
                // de um item malformado.
                registro.warn("perfil nao atualizado para um cliente da rodada", e);
            }
        }
    }

    private void gravarComportamento(ComportamentoDoCliente comportamento) {
        var atributos = MapeamentoDePerfil.atributosDeComportamento(comportamento);

        var atribuicoes = new ArrayList<String>(atributos.size() + 1);
        var nomes = new LinkedHashMap<String, String>();
        var valores = new LinkedHashMap<String, AttributeValue>();

        int i = 0;
        for (var atributo : atributos.entrySet()) {
            // Placeholders #n / :v em vez dos nomes literais. O DynamoDB tem uma
            // lista longa de palavras reservadas e o erro que ela produz aponta
            // para a expressao, nao para o campo.
            var nome = "#a" + i;
            var valor = ":a" + i;
            nomes.put(nome, atributo.getKey());
            valores.put(valor, atributo.getValue());
            atribuicoes.add(nome + " = " + valor);
            i++;
        }

        nomes.put("#calculadoEm", "calculadoEm");
        valores.put(":calculadoEm", MapeamentoDePerfil.texto(Instant.now().toString()));
        atribuicoes.add("#calculadoEm = :calculadoEm");

        dynamo.updateItem(UpdateItemRequest.builder()
                .tableName(tabela)
                .key(MapeamentoDePerfil.chave(comportamento.clienteId(), SK_COMPORTAMENTO))
                .updateExpression("SET " + String.join(", ", atribuicoes))
                .expressionAttributeNames(nomes)
                .expressionAttributeValues(valores)
                .build());
    }

    /**
     * {@code SET gatilhosAtivos = if_not_exists(gatilhosAtivos, :g)}.
     *
     * <p>Esta linha reproduz exatamente a semântica de {@code PerfisEmMemoria}, que
     * é peculiar e deliberada: {@code doZero} pega os gatilhos da agregação na
     * primeira vez que vê o cliente, e {@code mesclar} os <em>preserva</em> em toda
     * atualização seguinte. Ou seja, gatilho entra uma vez e depois pertence a quem
     * escrever o item de cadastro.
     *
     * <p>Numa JVM só isso era inofensivo. Com dois produtores reais passa a ser
     * pergunta de desenho — <em>quem é o dono do gatilho?</em> — e a resposta do
     * desenho é: os sistemas de origem. Enquanto eles não existem, o
     * {@code if_not_exists} mantém o comportamento de hoje sem inventar autoridade
     * que a agregação de busca não tem.
     *
     * <p>Lote vazio não escreve. Em memória {@code doZero} criava o perfil com um
     * conjunto vazio de gatilhos; aqui a ausência do atributo é lida como conjunto
     * vazio pelo mapeamento, então o comportamento observável é o mesmo com uma
     * escrita a menos por cliente.
     */
    private void semearGatilhos(ComportamentoDoCliente comportamento) {
        if (comportamento.gatilhos().isEmpty()) {
            return;
        }

        dynamo.updateItem(UpdateItemRequest.builder()
                .tableName(tabela)
                .key(MapeamentoDePerfil.chave(comportamento.clienteId(), SK_CADASTRO))
                .updateExpression("SET #g = if_not_exists(#g, :g)")
                .expressionAttributeNames(Map.of("#g", "gatilhosAtivos"))
                .expressionAttributeValues(Map.of(
                        ":g", MapeamentoDePerfil.listaDeTextos(comportamento.gatilhos())))
                .build());
    }

    /**
     * Grava os <em>fixtures</em> dos perfis de demonstração — nos <strong>dois</strong>
     * itens.
     *
     * <p>No {@code CADASTRO} vão elegibilidade, produto contratado, gatilho e
     * situação da conta: dado que, no desenho, vem dos sistemas de origem e que esta
     * aplicação apenas cacheia. Sem eles a Peneira não teria o que vetar e metade da
     * demonstração deixaria de existir.
     *
     * <p>No {@code COMPORTAMENTO} vai o <strong>histórico</strong>, e esquecê-lo foi
     * um defeito que o portão de NDCG pegou. A medição:
     *
     * <pre>
     * q=cdb, pf-investidor
     *   memoria    HISTORICO cdb · POPULAR contratar cdb   (2 linhas)
     *   DynamoDB                   POPULAR contratar cdb   (1 linha)
     *   NDCG@5     1,0000 -> 0,6131
     * </pre>
     *
     * <p>Os dois descartavam {@code CDB} por {@code PRODUTO_JA_CONTRATADO} —
     * idêntico e correto. O que faltava era que o histórico dos perfis de
     * demonstração é <em>escrito à mão</em> em {@code PerfisDaDemonstracao}, não
     * derivado dos eventos semeados, e ele mora no item de comportamento. Semeando
     * só o cadastro, oito clientes perdiam a camada {@code HISTORICO}.
     *
     * <p>Sobrescreve a cada inicialização, de propósito, e isso reproduz a memória
     * exatamente: lá o construtor de {@code PerfisEmMemoria} também reaplica o
     * fixture a cada subida. Fixture que depende do estado deixado pela execução
     * anterior é fixture que falha na segunda demonstração.
     *
     * <p>E não semeia afinidade, tendência nem fadiga — <strong>de propósito</strong>.
     * Elas são <em>calculadas</em> a partir dos eventos de demonstração, pelo mesmo
     * caminho que calcularia as de um cliente real. Escrevê-las à mão deixou de
     * funcionar quando a afinidade ganhou decaimento: a primeira rodada substituiria
     * o número inventado por zero. Para um cliente de verdade, nada aqui se aplica —
     * o histórico dele vem do log restaurado.
     */
    public void semearDemonstracao(Map<String, Perfil> perfis) {
        for (var entrada : perfis.entrySet()) {
            var perfil = entrada.getValue();
            try {
                if (!perfil.historico().isEmpty()) {
                    dynamo.updateItem(UpdateItemRequest.builder()
                            .overrideConfiguration(SETUP)
                            .tableName(tabela)
                            .key(MapeamentoDePerfil.chave(entrada.getKey(), SK_COMPORTAMENTO))
                            .updateExpression("SET #h = :h")
                            .expressionAttributeNames(Map.of("#h", "historico"))
                            .expressionAttributeValues(Map.of(
                                    ":h", MapeamentoDePerfil.listaDeTextos(perfil.historico())))
                            .build());
                }

                dynamo.updateItem(UpdateItemRequest.builder()
                        .overrideConfiguration(SETUP)
                        .tableName(tabela)
                        .key(MapeamentoDePerfil.chave(entrada.getKey(), SK_CADASTRO))
                        .updateExpression("SET #gat = :gat, #prod = :prod, "
                                + "#eleg = :eleg, #sit = :sit, #em = :em")
                        .expressionAttributeNames(Map.of(
                                "#gat", "gatilhosAtivos",
                                "#prod", "produtosContratados",
                                "#eleg", "elegibilidades",
                                "#sit", "situacaoDaConta",
                                "#em", "atualizadoEm"))
                        .expressionAttributeValues(Map.of(
                                ":gat", MapeamentoDePerfil.listaDeTextos(perfil.gatilhosAtivos()),
                                ":prod", MapeamentoDePerfil.listaDeTextos(perfil.produtosContratados()),
                                ":eleg", MapeamentoDePerfil.listaDeTextos(perfil.elegibilidades()),
                                ":sit", MapeamentoDePerfil.texto(perfil.situacaoDaConta().name()),
                                ":em", MapeamentoDePerfil.texto(Instant.now().toString())))
                        .build());
            } catch (RuntimeException e) {
                registro.warn("perfil de demonstracao '{}' nao semeado", entrada.getKey(), e);
            }
        }
        registro.info("{} perfis de demonstracao semeados no DynamoDB (cadastro + historico)",
                perfis.size());
    }
}
