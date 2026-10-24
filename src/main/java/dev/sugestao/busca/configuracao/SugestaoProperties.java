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
        List<String> tiposSugeriveis,

        /*
         * Liga o parametro ?explicar=true. Fica DESLIGADO em producao: a lista
         * de descartes diz quais produtos o cliente nao pode contratar, e isso
         * e informacao sobre a pessoa, nao sobre a busca.
         */
        boolean explicacaoHabilitada,

        Aprendizado aprendizado) {

    public record Solr(String baseUrl, String colecao) { }

    public record Prazo(long solrMs, long perfilMs, long totalMs) { }

    /** Teto por origem na lista final. Sem cota, a lista colapsa na origem mais popular. */
    public record Cota(int historico, int evento) { }

    /**
     * O caminho de escrita. Nada aqui esta no orcamento de 30 ms: sao os
     * numeros do que roda fora da requisicao.
     *
     * <p>{@code gatilhoManual} liga o POST /v1/aprendizado/rodar, que existe
     * para a demonstracao. DESLIGAR EM PRODUCAO: e um gatilho que da para
     * disparar em laco.
     */
    public record Aprendizado(
            long intervaloMs,
            int janelaVocabularioDias,
            int janelaComportamentoHoras,
            int tamanhoDoHistorico,
            int loteDeConsumo,
            int tetoDaFila,
            int tetoDeEventos,

            /*
             * O prazo da publicacao no indice. Nao tem relacao com os 18 ms da
             * leitura: aqui ninguem esta esperando, e um update seguido de
             * commit leva segundos. Reusar o prazo da leitura foi o bug que
             * fez o indice parar de aprender em silencio.
             */
            long publicacaoTimeoutMs,

            boolean gatilhoManual) { }
}
