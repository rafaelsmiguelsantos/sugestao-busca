package dev.sugestao.busca.configuracao;

import dev.sugestao.busca.adaptador.entrada.agendador.AgendadorDeAprendizado;
import dev.sugestao.busca.adaptador.saida.agregado.AgregadosEmMemoria;
import dev.sugestao.busca.adaptador.saida.fila.FilaEmMemoria;
import dev.sugestao.busca.adaptador.saida.perfil.PerfisDaDemonstracao;
import dev.sugestao.busca.adaptador.saida.perfil.PerfisEmMemoria;
import dev.sugestao.busca.adaptador.saida.resiliencia.CatalogoComDisjuntor;
import dev.sugestao.busca.adaptador.saida.resiliencia.Disjuntor;
import dev.sugestao.busca.adaptador.saida.solr.CatalogoCuradoSolr;
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
import dev.sugestao.busca.aplicacao.porta.saida.EscritorDePerfil;
import dev.sugestao.busca.aplicacao.porta.saida.FilaDeEventos;
import dev.sugestao.busca.aplicacao.porta.saida.PublicadorDeVocabulario;
import dev.sugestao.busca.aplicacao.porta.saida.RegistroDeOfertas;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDeAgregados;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.ClassificadorDeOrigem;
import dev.sugestao.busca.dominio.Cotas;
import dev.sugestao.busca.dominio.Misturador;
import dev.sugestao.busca.dominio.Peneira;
import dev.sugestao.busca.dominio.PesosDeRanking;
import dev.sugestao.busca.dominio.Rankeador;
import dev.sugestao.busca.dominio.evento.Saneador;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

        var catalogo = new CatalogoSolr(solr, props.solr().colecao(), consulta);

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
                        p.afinidadeMaxima(), p.margemParaLiderar()),
                Prazos.de(props.prazo().solrMs(), props.prazo().perfilMs(), props.prazo().totalMs()));
    }

    @Bean
    public SugerirSugestoes sugerirSugestoes(
            CatalogoDeSugestoes catalogo,
            RepositorioDePerfil perfis,
            ClassificadorDeOrigem classificador,
            Peneira peneira,
            Rankeador rankeador,
            Misturador misturador,
            ParametrosDeSugestao parametros) {

        return new SugerirSugestoesCasoDeUso(
                catalogo, perfis, classificador, peneira, rankeador, misturador, parametros);
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

    @Bean
    public RepositorioDeAgregados repositorioDeAgregados(SugestaoProperties props) {
        return new AgregadosEmMemoria(props.aprendizado().tetoDeEventos());
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
        return new ParametrosDeAprendizado(
                Duration.ofDays(a.janelaVocabularioDias()),
                Duration.ofHours(a.janelaComportamentoHoras()),
                a.tamanhoDoHistorico(),
                a.loteDeConsumo(),
                props.kAnonimatoMinimo());
    }

    @Bean
    public RegistrarEventos registrarEventos(FilaDeEventos fila, Saneador saneador) {
        return new RegistrarEventosCasoDeUso(fila, saneador);
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
