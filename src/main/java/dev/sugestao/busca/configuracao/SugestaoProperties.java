package dev.sugestao.busca.configuracao;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Tudo o que o desenho chama de "parâmetro" mora aqui, não em constante no
 * código. Os prazos são por dependência e cada um tem caminho alternativo;
 * nenhum deles tem nova tentativa, porque a próxima tecla já vem a caminho.
 *
 * <p>Este é um tipo de borda: ele conhece Spring e conhece arquivo de
 * propriedades. O domínio e a aplicação recebem {@code Cotas}, {@code Prazos} e
 * {@code ParametrosDeSugestao}, que são valores simples, e por isso continuam
 * testáveis sem contexto nenhum.
 */
@ConfigurationProperties(prefix = "sugestao")
public record SugestaoProperties(
        Solr solr,
        Prazo prazo,
        int prefixoMinimo,
        int resultadoMaximo,
        int candidatosSolr,
        Cota cota,
        int kAnonimatoMinimo,
        List<String> tiposSugeriveis) {

    public record Solr(String baseUrl, String colecao) { }

    public record Prazo(long solrMs, long perfilMs, long totalMs) { }

    /** Teto por origem na lista final. Sem cota, a lista colapsa na origem mais popular. */
    public record Cota(int historico, int evento) { }
}
