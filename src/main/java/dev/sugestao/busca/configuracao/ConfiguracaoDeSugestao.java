package dev.sugestao.busca.configuracao;

import dev.sugestao.busca.adaptador.entrada.agendador.AgendadorDeAprendizado;
import dev.sugestao.busca.adaptador.saida.agregado.AgregadosEmMemoria;
import dev.sugestao.busca.adaptador.saida.agregado.EventosDaDemonstracao;
import dev.sugestao.busca.adaptador.saida.fila.FilaEmMemoria;
import dev.sugestao.busca.adaptador.saida.perfil.PerfisComCache;
import dev.sugestao.busca.adaptador.saida.perfil.PerfisDaDemonstracao;
import dev.sugestao.busca.adaptador.saida.perfil.PerfisEmMemoria;
import dev.sugestao.busca.adaptador.saida.resiliencia.CatalogoComDisjuntor;
import dev.sugestao.busca.adaptador.saida.resiliencia.Disjuntor;
import dev.sugestao.busca.adaptador.saida.solr.CatalogoCuradoSolr;
import dev.sugestao.busca.adaptador.saida.metrica.MedidorMicrometer;
import dev.sugestao.busca.adaptador.saida.solr.CatalogoSolr;
import dev.sugestao.busca.adaptador.saida.solr.ConsultaDeCandidatos;
import dev.sugestao.busca.adaptador.saida.solr.PublicadorSolr;
import dev.sugestao.busca.adaptador.saida.solr.RegistroSolr;
import dev.sugestao.busca.aplicacao.ParametrosDeAprendizado;
import dev.sugestao.busca.aplicacao.ParametrosDeSugestao;
import dev.sugestao.busca.aplicacao.Prazos;
import dev.sugestao.busca.aplicacao.casodeuso.AprenderComComportamentoCasoDeUso;
import dev.sugestao.busca.aplicacao.casodeuso.CadastrarOfertaCasoDeUso;
import dev.sugestao.busca.aplicacao.casodeuso.RegistrarEventosCasoDeUso;
import dev.sugestao.busca.aplicacao.casodeuso.SugerirSugestoesCasoDeUso;
import dev.sugestao.busca.aplicacao.porta.entrada.AprenderComComportamento;
import dev.sugestao.busca.aplicacao.porta.entrada.CadastrarOferta;
import dev.sugestao.busca.aplicacao.porta.entrada.RegistrarEventos;
import dev.sugestao.busca.aplicacao.porta.entrada.SugerirSugestoes;
import dev.sugestao.busca.aplicacao.porta.saida.CatalogoCurado;
import dev.sugestao.busca.aplicacao.porta.saida.CatalogoDeSugestoes;
import dev.sugestao.busca.aplicacao.porta.saida.MedidorDeBusca;
import dev.sugestao.busca.dominio.agregado.PesosDeFadiga;
import io.micrometer.core.instrument.MeterRegistry;
import dev.sugestao.busca.aplicacao.porta.saida.EscritorDePerfil;
import dev.sugestao.busca.aplicacao.porta.saida.FilaDeEventos;
import dev.sugestao.busca.aplicacao.porta.saida.PublicadorDeVocabulario;
import dev.sugestao.busca.aplicacao.porta.saida.RegistroDeOfertas;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDeAgregados;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.ClassificadorDeOrigem;
import dev.sugestao.busca.dominio.agregado.PesosDeAfinidade;
import dev.sugestao.busca.dominio.Cotas;
import dev.sugestao.busca.dominio.Misturador;
import dev.sugestao.busca.dominio.Peneira;
import dev.sugestao.busca.dominio.PesosDeRanking;
import dev.sugestao.busca.dominio.Rankeador;
import dev.sugestao.busca.dominio.Recuperador;
import dev.sugestao.busca.dominio.Substituto;
import dev.sugestao.busca.dominio.Substitutos;
import dev.sugestao.busca.dominio.evento.Saneador;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import dev.sugestao.busca.adaptador.saida.dynamo.AgregadosComLogDuravel;
import dev.sugestao.busca.adaptador.saida.dynamo.PerfisNoDynamo;
import dev.sugestao.busca.adaptador.saida.dynamo.TabelasDoDynamo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * O único lugar do projeto que sabe quem é a implementação de cada porta.
 *
 * <p>É aqui que a inversão de dependência deixa de ser desenho e vira objeto: o
 * caso de uso recebe {@code CatalogoDeSugestoes} e {@code RepositorioDePerfil},
 * e quem decide que hoje eles são Solr e um mapa em memória é esta classe. Trocar
 * o mapa por DynamoDB é mudar uma linha deste arquivo.
 *
 * <p>Nada abaixo de {@code adaptador} tem anotação de Spring. Domínio e
 * aplicação são Java comum, construídos aqui com {@code new}.
 */
@Configuration
public class ConfiguracaoDeSugestao {

    /**
     * O timeout de requisição é o que faz o prazo do caso de uso valer de
     * verdade. O cancelamento do {@code Future} interrompe a thread virtual,
     * mas uma chamada HTTP surda à interrupção ainda seguraria o fechamento do
     * executor depois do prazo vencido. Com os dois, o prazo é prazo.
     *
     * <p>O tempo de conexão é outro assunto e por isso é outro número: ele é
     * pago uma vez e amortizado pelo keep-alive, não a cada tecla digitada.
     */
    @Bean(destroyMethod = "close")
    public SolrClient solrDeLeitura(SugestaoProperties props) {
        return new HttpJdkSolrClient.Builder(props.solr().baseUrl())
                .withConnectionTimeout(1, TimeUnit.SECONDS)
                .withRequestTimeout(props.prazo().solrMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * Um segundo cliente, e não o mesmo com outro prazo, porque o prazo é
     * propriedade do cliente HTTP e os dois caminhos querem o oposto um do
     * outro.
     *
     * <p>A leitura tem 18 ms e precisa desistir: a pessoa está digitando, e uma
     * lista que chega depois da próxima tecla é pior que lista nenhuma. A
     * publicação roda sem ninguém esperando e <em>não pode</em> desistir — um
     * {@code update} com milhares de documentos seguido de {@code commit} leva
     * segundos, e abandoná-lo no meio deixa o índice com parte da janela nova e
     * parte da antiga.
     *
     * <p>Compartilhar um cliente só entre os dois parece economia e é
     * acoplamento: foi assim que a primeira versão ficou, e o resultado era a
     * publicação estourando em 18 ms a cada rodada — silenciosamente, porque o
     * agendador engole o erro e tenta de novo. O índice simplesmente parava de
     * aprender.
     */
    @Bean(destroyMethod = "close")
    public SolrClient solrDePublicacao(SugestaoProperties props) {
        return new HttpJdkSolrClient.Builder(props.solr().baseUrl())
                .withConnectionTimeout(5, TimeUnit.SECONDS)
                .withRequestTimeout(props.aprendizado().publicacaoTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    @Bean
    public ConsultaDeCandidatos consultaDeCandidatos(SugestaoProperties props) {
        return new ConsultaDeCandidatos(props.kAnonimatoMinimo(), props.tiposSugeriveis());
    }

    /**
     * O catálogo, possivelmente embrulhado no disjuntor.
     *
     * <p>É o único lugar do projeto que sabe que existe disjuntor, e é esse o
     * ponto: {@code CatalogoSolr} não sabe, o caso de uso não sabe, o domínio
     * não sabe. Empilhar decorador sobre a porta é uma linha aqui, e desligar o
     * disjuntor é não empilhar — não um disjuntor que finge estar fechado. Um
     * no-op passaria a existir no caminho de execução para não fazer nada, e
     * "não fazer nada" é o tipo de coisa que, um dia, faz algo.
     *
     * <p>A ordem importa e é esta: disjuntor <em>por fora</em> do adaptador. Por
     * dentro ele contaria falhas que o adaptador já teria pago em rede; por fora
     * ele recusa antes de abrir conexão, que é a razão de existir.
     */
    @Bean
    public CatalogoDeSugestoes catalogoDeSugestoes(
            @Qualifier("solrDeLeitura") SolrClient solr,
            SugestaoProperties props,
            ConsultaDeCandidatos consulta) {

        var busca = props.busca() == null
                ? SugestaoProperties.Busca.padrao()
                : props.busca();

        var catalogo = new CatalogoSolr(
                solr, props.solr().colecao(), consulta, busca.toleranciaHabilitada());

        var config = props.disjuntor() == null
                ? SugestaoProperties.Disjuntor.padrao()
                : props.disjuntor();

        if (!config.habilitado()) {
            return catalogo;
        }

        return new CatalogoComDisjuntor(catalogo, new Disjuntor(
                "catalogo-solr",
                config.janela(),
                config.minimoDeChamadas(),
                config.limiteDeFalhas(),
                Duration.ofMillis(config.esperaMs()),
                config.sondas()));
    }

    /**
     * Um objeto só atendendo as duas portas de perfil — mas elas continuam
     * separadas, e o compilador verifica. O caso de uso de sugestão recebe
     * {@code RepositorioDePerfil} e não tem como escrever; o de aprendizado
     * recebe {@code EscritorDePerfil} e não tem como ser esperado por ninguém.
     */
    @Bean
    public PerfisEmMemoria perfisEmMemoria() {
        return new PerfisEmMemoria(PerfisDaDemonstracao.iniciais());
    }

    // -----------------------------------------------------------------------
    // DynamoDB. Opcional, e quando ligado troca os adaptadores de memória sem
    // que o domínio saiba — nenhuma linha dele mudou para isto existir.
    // -----------------------------------------------------------------------

    /**
     * O cliente do DynamoDB, com <strong>timeout próprio</strong>.
     *
     * <p>Esse timeout é a parte que não dá para esquecer, e o caso de uso já
     * explicava por quê: <em>"o que transforma o prazo em ficção são duas coisas
     * juntas: o cancelamento com interrupção em `aguardar`, e o timeout de
     * requisição configurado no próprio cliente HTTP do adaptador. Sem o segundo,
     * uma dependência surda à interrupção seguraria a resposta depois do prazo
     * vencido."</em> O {@code aguardar} desiste em 8 ms; sem o timeout aqui, a
     * thread virtual continuaria presa até o default do SDK, que é de segundos.
     *
     * <p><strong>Nova tentativa desligada.</strong> O SDK tenta três vezes por
     * padrão. Num typeahead isso é a pior configuração possível: a próxima tecla
     * já vem a caminho, então uma segunda tentativa serve uma resposta que ninguém
     * mais quer, gastando a conexão que a tecla seguinte precisa. É a mesma regra
     * que vale para o Solr em todo o projeto — nenhuma etapa tem nova tentativa.
     *
     * <p>Credencial estática e falsa quando há {@code endpoint} configurado: o
     * DynamoDB Local aceita qualquer uma, mas o SDK <em>exige</em> que exista uma
     * para assinar. Sem isto a cadeia de credenciais vai procurar perfil, variável
     * de ambiente e metadata de instância — e falha com uma mensagem sobre IAM que
     * não tem nada a ver com o problema.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "sugestao.dynamo", name = "habilitado", havingValue = "true")
    public DynamoDbClient dynamoDbClient(SugestaoProperties props) {
        var config = props.dynamo() == null
                ? SugestaoProperties.Dynamo.padrao()
                : props.dynamo();

        return cliente(config, Duration.ofMillis(config.timeoutMs()));
    }

    /**
     * Um cliente com prazo próprio, e o SDK tem <strong>três camadas</strong> de
     * prazo que não são a mesma coisa.
     *
     * <p>Isto custou duas tentativas, e vale registrar porque o sintoma aponta
     * para o lugar errado:
     *
     * <ol>
     *   <li>{@code apiCallTimeout} — o orçamento da chamada inteira, contado pelo
     *       temporizador do SDK. <em>Sobreponível por requisição.</em></li>
     *   <li>{@code apiCallAttemptTimeout} — o de cada tentativa.
     *       <em>Sobreponível por requisição.</em></li>
     *   <li>{@code connectionTimeout} e {@code socketTimeout} do cliente HTTP —
     *       <strong>fixados na construção e NÃO sobreponíveis por requisição.</strong></li>
     * </ol>
     *
     * <p>Com 200 ms nas três, a criação de tabela falhava com
     * {@code ApiCallTimeoutException}; sobrepondo as duas primeiras por requisição,
     * passou a falhar com {@code SdkClientException: Read timed out} — a terceira
     * camada, que o override não alcança. E só na <em>primeira</em> chamada, porque
     * ela paga resolução de endpoint e carga de classe.
     *
     * <p>Daí dois clientes em vez de um afrouxado: <strong>prazo pertence a uma
     * carga de trabalho, não a um cliente.</strong> O do caminho de leitura tem de
     * ser apertado porque o orçamento do perfil é 8 ms; o da inicialização não tem
     * ninguém digitando do outro lado. Afrouxar um só para caber o outro teria
     * quebrado a única coisa que o prazo protege.
     */
    private static DynamoDbClient cliente(SugestaoProperties.Dynamo config, Duration prazo) {
        var construtor = DynamoDbClient.builder()
                .region(Region.of(config.regiao()))
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(prazo)
                        .socketTimeout(prazo)
                        .build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(prazo)
                        .apiCallAttemptTimeout(prazo)
                        // O SDK tenta tres vezes por padrao. Num typeahead e a pior
                        // configuracao possivel: a proxima tecla ja vem a caminho,
                        // entao a segunda tentativa serve uma resposta que ninguem
                        // mais quer, gastando a conexao que a tecla seguinte precisa.
                        // Nenhuma etapa deste projeto tem nova tentativa.
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .build());

        if (!config.endpoint().isBlank()) {
            // Credencial estatica e falsa: o DynamoDB Local aceita qualquer uma,
            // mas o SDK EXIGE que exista uma para assinar. Sem isto a cadeia de
            // credenciais procura perfil, variavel de ambiente e metadata de
            // instancia, e falha com uma mensagem sobre IAM que nao tem nada a ver
            // com o problema.
            construtor
                    .endpointOverride(URI.create(config.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }

        return construtor.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "sugestao.dynamo", name = "habilitado", havingValue = "true")
    public PerfisNoDynamo perfisNoDynamo(DynamoDbClient dynamo, SugestaoProperties props) {
        var config = props.dynamo();

        // Cliente de inicializacao, fechado ao sair. Ver cliente(...) para o
        // motivo de ele existir separado.
        try (var setup = cliente(config, Duration.ofSeconds(20))) {
            if (config.criaTabelas()) {
                TabelasDoDynamo.criar(setup, config.tabelaDePerfis(), config.tabelaDeEventos());
            }

            // Os fatos dos oito perfis de demonstração. Sem isto, subir com o
            // DynamoDB daria oito clientes sem elegibilidade e sem situação de
            // conta: a Peneira não teria o que vetar e metade da demonstração
            // deixaria de existir.
            new PerfisNoDynamo(setup, config.tabelaDePerfis(), true)
                    .semearDemonstracao(PerfisDaDemonstracao.iniciais());
        }

        return new PerfisNoDynamo(
                dynamo, config.tabelaDePerfis(), config.leEmConsistenciaForte());
    }

    /**
     * A porta de <em>escrita</em> de perfil.
     *
     * <p>Existe por causa da ambiguidade que o DynamoDB introduz: dois beans
     * passam a implementar {@code EscritorDePerfil}, e o Spring não teria como
     * escolher.
     *
     * <p><strong>A referência de método não é estilo, é o que faz isto
     * funcionar.</strong> Devolver o objeto escolhido direto registraria um bean
     * cujo tipo real também implementa {@code RepositorioDePerfil} — e aí
     * existiriam <em>dois</em> beans primários para a porta de leitura, com o
     * contexto recusando subir. Já aconteceu aqui, e a mensagem do Spring é
     * clara: {@code more than one 'primary' bean found}.
     *
     * <p>{@code escolhido::atualizar} produz um objeto que implementa
     * <em>somente</em> a porta de escrita, porque {@code EscritorDePerfil} tem um
     * método só. A separação por direção, que até agora era verificada pelo
     * compilador nos casos de uso, passa a ser verificada também pelo contêiner:
     * este bean é <strong>incapaz</strong> de ser injetado onde se espera leitura.
     */
    @Bean
    @Primary
    public EscritorDePerfil escritorDePerfil(
            PerfisEmMemoria emMemoria, ObjectProvider<PerfisNoDynamo> noDynamo) {

        var doDynamo = noDynamo.getIfAvailable();
        EscritorDePerfil escolhido = doDynamo != null ? doDynamo : emMemoria;
        return escolhido::atualizar;
    }

    /**
     * A porta de <em>leitura</em> de perfil, possivelmente com cache.
     *
     * <p>Mesmo padrão do disjuntor no catálogo, e aqui ele tem uma consequência
     * que vale olhar: <strong>só a leitura é decorada.</strong> O bean acima
     * continua sendo o que atende {@code EscritorDePerfil}, direto, sem passar
     * por cache nenhum. Então a agregação escreve no mapa e as leituras seguintes
     * vão vê-lo quando o TTL vencer — que é exatamente o comportamento descrito
     * em {@link PerfisComCache}, e não um efeito colateral desta fiação.
     *
     * <p>O {@code @Primary} é necessário e não é decoração. {@code PerfisEmMemoria}
     * implementa as duas portas, então existem dois beans do tipo
     * {@code RepositorioDePerfil} e o Spring não teria como escolher. A anotação
     * diz qual é a porta de leitura do sistema; o outro continua acessível por
     * tipo concreto, que é como esta classe o injeta aqui.
     *
     * <p>Desligar é devolver o adaptador cru — não um cache de TTL zero. Um cache
     * que não guarda nada ainda paga a estrutura, ainda aparece nas métricas com
     * 0% de acerto, e ainda é um objeto no caminho de execução para não fazer
     * nada.
     */
    @Bean
    @Primary
    public RepositorioDePerfil repositorioDePerfil(
            PerfisEmMemoria emMemoria,
            ObjectProvider<PerfisNoDynamo> noDynamo,
            SugestaoProperties props,
            MeterRegistry registro) {

        // O adaptador de base: DynamoDB quando ligado, memória quando não. O
        // cache decora qualquer um dos dois sem saber qual é — que é o ponto de
        // ambos implementarem a mesma porta.
        //
        // E é aqui que a troca fica visível: esta linha é toda a diferença entre
        // um perfil que morre no restart e um que não morre. Nem o caso de uso,
        // nem o domínio, nem um único teste do domínio muda por causa dela.
        var doDynamo = noDynamo.getIfAvailable();
        RepositorioDePerfil base = doDynamo != null ? doDynamo : emMemoria;

        var config = props.cacheDePerfil() == null
                ? SugestaoProperties.CacheDePerfil.padrao()
                : props.cacheDePerfil();

        if (!config.habilitado()) {
            return base;
        }

        var comCache = new PerfisComCache(
                base, Duration.ofMillis(config.ttlMs()), config.tetoDeChaves());

        // As três saem sempre que o cache está ligado, independente de
        // sugestao.medicao.habilitada. Aquele interruptor governa a porta
        // MedidorDeBusca, que é medição de qualidade de busca; estes são
        // contadores de infraestrutura, da mesma natureza dos de JVM que o
        // Actuator já publica — e sem a taxa de acerto não há como saber se o TTL
        // escolhido corresponde à velocidade real de digitação.
        FunctionCounter.builder("sugestao.perfil.cache.acertos", comCache,
                        p -> p.estatisticas().acertos())
                .description("leituras de perfil servidas pelo cache da sessao de digitacao")
                .register(registro);

        FunctionCounter.builder("sugestao.perfil.cache.erros", comCache,
                        p -> p.estatisticas().erros())
                .description("leituras de perfil que foram ao adaptador")
                .register(registro);

        Gauge.builder("sugestao.perfil.cache.itens", comCache,
                        p -> p.estatisticas().itens())
                .description("perfis guardados agora")
                .register(registro);

        return comCache;
    }

    /**
     * A etapa de recuperação, e a tabela de linhas substitutas que ela consulta.
     *
     * <p>A tradução de {@code SugestaoProperties.LinhaSubstituta} para
     * {@code Substituto} acontece aqui, e é onde a validação do domínio é
     * exercida: jornada ausente, termo vazio ou destino absoluto <strong>derrubam
     * a subida</strong>. É o lugar certo para esse erro — ele é de quem editou a
     * configuração, aparece uma vez, e aparece antes de a primeira pessoa buscar.
     * A alternativa seria uma linha quebrada aparecendo na tela de quem já tinha
     * recebido lista vazia.
     *
     * <p>Desligado é tabela vazia, e não um recuperador ausente: o
     * {@link Recuperador} com {@link Substitutos#NENHUM} sai no primeiro
     * {@code if} e devolve {@code Optional.empty()}. Aqui o no-op é um valor
     * declarado, não um objeto que finge.
     */
    @Bean
    public Recuperador recuperador(SugestaoProperties props) {
        var config = props.recuperacao() == null
                ? SugestaoProperties.Recuperacao.padrao()
                : props.recuperacao();

        if (!config.ligada()) {
            return new Recuperador(Substitutos.NENHUM);
        }

        var declaradas = config.linhas().stream()
                .map(linha -> new Substituto(
                        linha.jornada(), linha.termo(), linha.rotulo(), linha.destino()))
                .toList();

        return new Recuperador(new Substitutos(declaradas));
    }

    @Bean
    public Peneira peneira(SugestaoProperties props) {
        return new Peneira(props.kAnonimatoMinimo());
    }

    @Bean
    public ClassificadorDeOrigem classificadorDeOrigem() {
        return new ClassificadorDeOrigem();
    }

    @Bean
    public Rankeador rankeador() {
        return new Rankeador();
    }

    @Bean
    public Misturador misturador() {
        return new Misturador();
    }

    @Bean
    public ParametrosDeSugestao parametrosDeSugestao(SugestaoProperties props) {
        var p = props.peso();
        return new ParametrosDeSugestao(
                props.prefixoMinimo(),
                props.prefixoMaximo(),
                props.candidatosSolr(),
                new Cotas(props.cota().historico(), props.cota().evento(), props.resultadoMaximo()),
                new PesosDeRanking(p.jornadaLider(), p.produtoDaJornada(),
                        p.afinidadeMaxima(), p.afinidadeDeProdutoMaxima(),
                        p.tendenciaMaxima(), p.fatorMaximo(), p.fadigaMinima(),
                        p.margemParaLiderar()),
                Prazos.de(props.prazo().solrMs(), props.prazo().perfilMs(), props.prazo().totalMs()));
    }

    /**
     * O medidor, ou o nulo quando a medição está desligada.
     *
     * <p>Mesmo padrão do disjuntor: desligar é não empilhar o adaptador, e o
     * {@code NULO} entra no lugar dele. O {@code MeterRegistry} vem do Actuator,
     * que já era dependência por causa do {@code /actuator/health}.
     */
    @Bean
    public MedidorDeBusca medidorDeBusca(SugestaoProperties props, MeterRegistry registro) {
        var medicao = props.medicao() == null
                ? SugestaoProperties.Medicao.padrao()
                : props.medicao();

        return medicao.habilitada() ? new MedidorMicrometer(registro) : MedidorDeBusca.NULO;
    }

    @Bean
    public SugerirSugestoes sugerirSugestoes(
            CatalogoDeSugestoes catalogo,
            RepositorioDePerfil perfis,
            ClassificadorDeOrigem classificador,
            Peneira peneira,
            Rankeador rankeador,
            Misturador misturador,
            Recuperador recuperador,
            ParametrosDeSugestao parametros,
            MedidorDeBusca medidor) {

        return new SugerirSugestoesCasoDeUso(catalogo, perfis, classificador, peneira,
                rankeador, misturador, recuperador, parametros, medidor);
    }

    // -----------------------------------------------------------------------
    // Caminho de escrita. Roda fora da requisição e nunca é esperado por ela.
    // -----------------------------------------------------------------------

    @Bean
    public Saneador saneador() {
        return new Saneador();
    }

    @Bean
    public FilaDeEventos filaDeEventos(SugestaoProperties props) {
        return new FilaEmMemoria(props.aprendizado().tetoDaFila());
    }

    /**
     * Semeado com cliques e adesões falsos, para a demonstração ter afinidade
     * sem ninguém precisar clicar à mão.
     *
     * <p>Antes, a afinidade da demonstração era escrita à mão no perfil. Deixou
     * de funcionar quando ela passou a ter decaimento e a sobrescrever: a
     * primeira rodada substituiria o número inventado por zero. Semeando evento,
     * a afinidade passa a ser <em>calculada</em> pelo mesmo caminho que
     * calcularia a de um cliente de verdade — e a demonstração prova o pipeline
     * em vez de contorná-lo.
     */
    @Bean
    public RepositorioDeAgregados repositorioDeAgregados(
            SugestaoProperties props, ObjectProvider<DynamoDbClient> dynamo) {

        var agregados = new AgregadosEmMemoria(props.aprendizado().tetoDeEventos());
        agregados.acumular(EventosDaDemonstracao.iniciais());

        var cliente = dynamo.getIfAvailable();
        if (cliente == null) {
            return agregados;
        }

        // O log durável, como DECORADOR — o quinto a entrar assim, depois do
        // disjuntor, do limite de taxa, da medição e do cache. Nenhuma porta
        // nova, nenhum parâmetro novo no caso de uso de aprendizado, nenhuma
        // linha de domínio.
        //
        // A ordem importa: a semeadura da demonstração acontece ACIMA, no
        // adaptador cru. Semear depois de decorar gravaria os eventos de
        // demonstração no log durável a cada inicialização, e em dez restarts o
        // log teria dez cópias do mesmo histórico inventado — poluindo
        // justamente o dado que este decorador existe para acumular.
        var comLog = new AgregadosComLogDuravel(
                agregados,
                cliente,
                props.dynamo().tabelaDeEventos(),
                Duration.ofDays(props.dynamo().retencaoDeEventosDias()));

        // E A RESTAURAÇÃO, que é o que faz a persistência entregar o que promete.
        //
        // Sem esta linha o perfil era gravado e perdido: ao subir, a rodada de
        // agregação recomputava-o a partir da janela em memória — que voltava com
        // apenas os eventos de demonstração — e sobrescrevia o aprendizado com
        // menos informação do que a view já tinha. Medido:
        //
        //     antes do restart   CDB 3.217298   LCI 2.274373
        //     depois do restart  CDB 3.082429   LCI 2.179031   <- a linha de base
        //
        // A janela restaurada é a do VOCABULÁRIO, a maior das duas, porque ela
        // contém a do comportamento. Restaurar só 24 h deixaria a afinidade — que
        // tem janela de 90 dias e meia-vida de 7 — nascer truncada a cada restart.
        comLog.restaurarDoLog(Duration.ofDays(props.aprendizado().janelaVocabularioDias()));

        return comLog;
    }

    @Bean
    public PublicadorDeVocabulario publicadorDeVocabulario(
            @Qualifier("solrDePublicacao") SolrClient solr, SugestaoProperties props) {
        return new PublicadorSolr(solr, props.solr().colecao());
    }

    @Bean
    public CatalogoCurado catalogoCurado(
            @Qualifier("solrDePublicacao") SolrClient solr, SugestaoProperties props) {
        return new CatalogoCuradoSolr(solr, props.solr().colecao());
    }

    @Bean
    public ParametrosDeAprendizado parametrosDeAprendizado(SugestaoProperties props) {
        var a = props.aprendizado();

        var afinidade = a.afinidade() == null
                ? SugestaoProperties.Afinidade.padrao()
                : a.afinidade();

        var fadiga = a.fadiga() == null
                ? SugestaoProperties.Fadiga.padrao()
                : a.fadiga();

        return new ParametrosDeAprendizado(
                Duration.ofDays(a.janelaVocabularioDias()),
                Duration.ofHours(a.janelaComportamentoHoras()),
                a.tamanhoDoHistorico(),
                a.loteDeConsumo(),
                props.kAnonimatoMinimo(),
                new PesosDeAfinidade(
                        Duration.ofDays(afinidade.janelaDias()),
                        Duration.ofDays(afinidade.meiaVidaDias()),
                        afinidade.pisoDeConfianca(),
                        afinidade.pisoDeConfiancaDoProduto(),
                        Duration.ofDays(afinidade.tendenciaCurtaDias()),
                        Duration.ofDays(afinidade.tendenciaLongaDias()),
                        afinidade.pisoDaTendencia()),
                new PesosDeFadiga(
                        fadiga.impressoesMinimas(),
                        fadiga.impressoesParaOTeto(),
                        Duration.ofDays(fadiga.janelaDias()),
                        fadiga.tamanhoMaximo()));
    }

    @Bean
    public RegistrarEventos registrarEventos(FilaDeEventos fila, Saneador saneador,
                                             MedidorDeBusca medidor) {
        return new RegistrarEventosCasoDeUso(fila, saneador, medidor);
    }

    @Bean
    public AprenderComComportamento aprenderComComportamento(
            FilaDeEventos fila,
            RepositorioDeAgregados agregados,
            CatalogoCurado catalogoCurado,
            PublicadorDeVocabulario publicador,
            EscritorDePerfil perfis,
            ParametrosDeAprendizado parametros) {

        return new AprenderComComportamentoCasoDeUso(
                fila, agregados, catalogoCurado, publicador, perfis, parametros);
    }

    @Bean
    public AgendadorDeAprendizado agendadorDeAprendizado(AprenderComComportamento aprender) {
        return new AgendadorDeAprendizado(aprender);
    }

    // -----------------------------------------------------------------------
    // Cadastro de oferta. Terceiro caminho do serviço, e o único sincrono que
    // pode falhar alto: tem uma jornada esperando confirmação de gravação.
    // -----------------------------------------------------------------------

    /**
     * Usa o cliente de publicação, e não o de leitura, pelo mesmo motivo do
     * publicador: {@code add} seguido de {@code commit} não cabe em 18 ms, e
     * abandoná-lo no meio grava oferta sem deixá-la visível.
     */
    @Bean
    public RegistroDeOfertas registroDeOfertas(
            @Qualifier("solrDePublicacao") SolrClient solr, SugestaoProperties props) {
        return new RegistroSolr(solr, props.solr().colecao());
    }

    @Bean
    public CadastrarOferta cadastrarOferta(RegistroDeOfertas registro) {
        return new CadastrarOfertaCasoDeUso(registro);
    }
}
