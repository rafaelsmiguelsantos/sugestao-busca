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

        /*
         * Teto do prefixo. Controle de custo, nao de relevancia: ver
         * ParametrosDeSugestao.prefixoMaximo.
         */
        int prefixoMaximo,

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

        Peso peso,

        Aprendizado aprendizado,

        /*
         * O que um cliente de navegador precisa para chamar este servico. Vem
         * vazio, e vazio significa nenhuma origem autorizada — nao todas.
         */
        Cors cors) {

    /**
     * Origens autorizadas e por quanto tempo o navegador guarda o preflight.
     *
     * <p>É um record separado para o padrão poder ser "desligado" sem ser nulo
     * espalhado pelo código: {@link #desligado()} devolve lista vazia, e lista
     * vazia é a decisão de não autorizar ninguém.
     */
    public record Cors(List<String> origens, long maxAgeSegundos) {

        public Cors {
            origens = origens == null ? List.of() : List.copyOf(origens);
            // 1800 s cobre uma sessao de uso. O Chrome limita o proprio teto em
            // 2 h, entao pedir mais nao ajuda; pedir menos faz o navegador
            // repetir o preflight no meio da digitacao.
            maxAgeSegundos = maxAgeSegundos <= 0 ? 1800 : maxAgeSegundos;
        }

        public static Cors desligado() {
            return new Cors(List.of(), 1800);
        }
    }

    public record Solr(String baseUrl, String colecao) { }

    public record Prazo(long solrMs, long perfilMs, long totalMs) { }

    /** Teto por origem na lista final. Sem cota, a lista colapsa na origem mais popular. */
    public record Cota(int historico, int evento) { }

    /**
     * Os multiplicadores do ranking. Sao multiplicadores e nao somas: somar
     * faria o peso depender da escala do score do Solr, que muda com o tamanho
     * do indice e com o prefixo digitado.
     */
    public record Peso(
            double jornadaLider,
            double produtoDaJornada,
            double afinidadeMaxima,
            double margemParaLiderar) { }

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
