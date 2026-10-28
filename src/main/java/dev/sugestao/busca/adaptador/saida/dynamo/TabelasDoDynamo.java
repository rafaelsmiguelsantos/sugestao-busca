package dev.sugestao.busca.adaptador.saida.dynamo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

import java.time.Duration;

/**
 * Cria as duas tabelas, de forma idempotente, na inicialização.
 *
 * <p>Equivale ao {@code criar-colecao.ps1} do Solr, com uma diferença de forma: lá
 * é script porque o schema do Solr é um arquivo que alguém edita; aqui a definição
 * é código, então o lugar honesto dela é o código. {@code docker compose up -d}
 * mais subir a aplicação dá um ambiente funcionando, sem passo intermediário que
 * dá para esquecer.
 *
 * <p><strong>Isto não é migração de schema.</strong> Serve para desenvolvimento e
 * demonstração. Em produção, tabela é infraestrutura e nasce de Terraform ou CDK —
 * não da aplicação que a usa, que não deveria ter permissão de
 * {@code CreateTable} em lugar nenhum.
 */
public final class TabelasDoDynamo {

    private static final Logger registro = LoggerFactory.getLogger(TabelasDoDynamo.class);

    /**
     * Prazo generoso <strong>só para estas chamadas</strong>, sobrepondo o do
     * cliente por requisição.
     *
     * <p>Isto já falhou, e a falha foi instrutiva. O cliente é configurado com
     * 200 ms porque o prazo do perfil é 8 ms e um timeout grande prenderia a
     * thread depois de a resposta já ter saído degradada. Só que
     * {@code CreateTable} não é leitura de perfil: é operação de painel de
     * controle, acontece uma vez na inicialização, e ninguém está digitando
     * enquanto ela roda. Com 200 ms a aplicação <em>recusava subir</em> —
     * {@code ApiCallTimeoutException} na criação da tabela.
     *
     * <p>A lição não é "aumente o timeout". É que <strong>um número de prazo
     * pertence a uma carga de trabalho, não a um cliente</strong>. Afrouxar o do
     * cliente para caber a inicialização teria quebrado a única coisa que o prazo
     * protege.
     */
    private static final Duration PRAZO_DE_SETUP = Duration.ofSeconds(20);

    private static final AwsRequestOverrideConfiguration SETUP =
            AwsRequestOverrideConfiguration.builder()
                    .apiCallTimeout(PRAZO_DE_SETUP)
                    .apiCallAttemptTimeout(PRAZO_DE_SETUP)
                    .build();

    private TabelasDoDynamo() {
    }

    /**
     * {@code PK} e {@code SK} de texto, cobrança sob demanda, e TTL onde faz
     * sentido.
     *
     * <p>{@code PAY_PER_REQUEST} em vez de capacidade provisionada porque um
     * typeahead tem tráfego de forma de sino e provisionar para o pico é pagar o
     * pico 24 h por dia. O DynamoDB Local ignora o modo de cobrança, mas o valor
     * aqui é o que iria para produção — e deixar {@code PROVISIONED} com
     * {@code 5/5} "porque é o exemplo da documentação" é como esse número chega
     * em produção.
     *
     * <p>TTL só na tabela de eventos. Elegibilidade não expira porque o cliente
     * parou de buscar, e pôr TTL no perfil apagaria o fato de outro sistema junto
     * com o comportamento velho.
     */
    public static void criar(DynamoDbClient dynamo, String tabelaDePerfis, String tabelaDeEventos) {
        criarTabela(dynamo, tabelaDePerfis);
        criarTabela(dynamo, tabelaDeEventos);
        habilitarTtl(dynamo, tabelaDeEventos);
    }

    private static void criarTabela(DynamoDbClient dynamo, String nome) {
        try {
            dynamo.createTable(CreateTableRequest.builder()
                    .overrideConfiguration(SETUP)
                    .tableName(nome)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName(MapeamentoDePerfil.PK)
                                    .attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder()
                                    .attributeName(MapeamentoDePerfil.SK)
                                    .attributeType(ScalarAttributeType.S).build())
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName(MapeamentoDePerfil.PK)
                                    .keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder()
                                    .attributeName(MapeamentoDePerfil.SK)
                                    .keyType(KeyType.RANGE).build())
                    .build());

            registro.info("tabela '{}' criada", nome);

        } catch (ResourceInUseException e) {
            // Já existe. É o caso normal a partir da segunda inicialização, e por
            // isso não é warn: um log de alarme que acontece sempre ensina a
            // ignorar o log.
            registro.debug("tabela '{}' ja existia", nome);
        }
    }

    private static void habilitarTtl(DynamoDbClient dynamo, String nome) {
        try {
            dynamo.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                    .overrideConfiguration(SETUP)
                    .tableName(nome)
                    .timeToLiveSpecification(TimeToLiveSpecification.builder()
                            .enabled(true)
                            .attributeName("expiraEm")
                            .build())
                    .build());

            registro.info("TTL habilitado em '{}' pelo atributo expiraEm", nome);

        } catch (RuntimeException e) {
            // Habilitar TTL duas vezes é erro no DynamoDB de verdade, e o
            // DynamoDB Local responde de forma própria. Nenhum dos dois é motivo
            // para a aplicação não subir: sem TTL a tabela cresce, e crescer é
            // problema de custo, não de correção.
            registro.debug("TTL de '{}' nao foi (re)habilitado: {}", nome, e.getMessage());
        }
    }
}
