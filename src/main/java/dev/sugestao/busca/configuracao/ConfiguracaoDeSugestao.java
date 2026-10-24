package dev.sugestao.busca.configuracao;

import dev.sugestao.busca.adaptador.saida.perfil.PerfisFalsosEmMemoria;
import dev.sugestao.busca.adaptador.saida.solr.CatalogoSolr;
import dev.sugestao.busca.adaptador.saida.solr.ConsultaDeCandidatos;
import dev.sugestao.busca.aplicacao.ParametrosDeSugestao;
import dev.sugestao.busca.aplicacao.Prazos;
import dev.sugestao.busca.aplicacao.casodeuso.SugerirSugestoesCasoDeUso;
import dev.sugestao.busca.aplicacao.porta.entrada.SugerirSugestoes;
import dev.sugestao.busca.aplicacao.porta.saida.CatalogoDeSugestoes;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.ClassificadorDeOrigem;
import dev.sugestao.busca.dominio.Cotas;
import dev.sugestao.busca.dominio.Misturador;
import dev.sugestao.busca.dominio.Peneira;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public SolrClient solrClient(SugestaoProperties props) {
        return new HttpJdkSolrClient.Builder(props.solr().baseUrl())
                .withConnectionTimeout(1, TimeUnit.SECONDS)
                .withRequestTimeout(props.prazo().solrMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    @Bean
    public ConsultaDeCandidatos consultaDeCandidatos(SugestaoProperties props) {
        return new ConsultaDeCandidatos(props.kAnonimatoMinimo(), props.tiposSugeriveis());
    }

    @Bean
    public CatalogoDeSugestoes catalogoDeSugestoes(
            SolrClient solrClient, SugestaoProperties props, ConsultaDeCandidatos consulta) {
        return new CatalogoSolr(solrClient, props.solr().colecao(), consulta);
    }

    @Bean
    public RepositorioDePerfil repositorioDePerfil() {
        return new PerfisFalsosEmMemoria();
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
    public Misturador misturador() {
        return new Misturador();
    }

    @Bean
    public ParametrosDeSugestao parametrosDeSugestao(SugestaoProperties props) {
        return new ParametrosDeSugestao(
                props.prefixoMinimo(),
                props.candidatosSolr(),
                new Cotas(props.cota().historico(), props.cota().evento(), props.resultadoMaximo()),
                Prazos.de(props.prazo().solrMs(), props.prazo().perfilMs(), props.prazo().totalMs()));
    }

    @Bean
    public SugerirSugestoes sugerirSugestoes(
            CatalogoDeSugestoes catalogo,
            RepositorioDePerfil perfis,
            ClassificadorDeOrigem classificador,
            Peneira peneira,
            Misturador misturador,
            ParametrosDeSugestao parametros) {

        return new SugerirSugestoesCasoDeUso(
                catalogo, perfis, classificador, peneira, misturador, parametros);
    }
}
