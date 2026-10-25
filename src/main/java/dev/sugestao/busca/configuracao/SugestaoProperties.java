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
        Cors cors,

        /*
         * O disjuntor da porta de leitura. Protege a dependencia e o processo,
         * nao a requisicao — o prazo e que protege a requisicao.
         */
        Disjuntor disjuntor,

        /* Quem pode chamar o que. Ver ConfiguracaoDeSeguranca. */
        Seguranca seguranca) {

    /**
     * Validação do token. Este serviço é servidor de recurso: valida e não
     * emite.
     *
     * <p>{@code habilitada=false} abre todas as rotas e faz a identidade voltar
     * a vir de cabeçalho. Existe para demonstrar sem emitir token, grita no log
     * na subida, e nunca deve chegar a produção — é o único parâmetro deste
     * arquivo cuja troca muda o serviço de "protegido" para "aberto".
     */
    public record Seguranca(boolean habilitada, String segredo, String emissor) {

        public Seguranca {
            emissor = emissor == null || emissor.isBlank()
                    ? "https://sugestao-busca.dev"
                    : emissor;
            segredo = segredo == null ? "" : segredo;
        }

        /**
         * O padrão é LIGADO com segredo vazio, o que faz a aplicação <em>não
         * subir</em> sem um segredo configurado.
         *
         * <p>É a escolha deliberadamente inconveniente. Um segredo padrão
         * embutido funcionaria em qualquer lugar, inclusive em produção, e
         * assinaria tokens que qualquer pessoa com acesso a este repositório
         * sabe forjar. Falhar na subida é ruidoso, acontece uma vez, e acontece
         * para quem está configurando — não para quem está sendo atacado.
         */
        public static Seguranca padrao() {
            return new Seguranca(true, "", "https://sugestao-busca.dev");
        }
    }

    /**
     * Quando parar de bater num índice que já respondeu que não está lá.
     *
     * <p>Os números são conservadores de propósito. Um disjuntor que abre fácil
     * troca indisponibilidade real por indisponibilidade inventada, e a segunda
     * é pior: ela acontece quando a dependência estava boa.
     */
    public record Disjuntor(
            boolean habilitado,

            /* Tamanho da janela de amostras. */
            int janela,

            /*
             * Minimo de chamadas antes de decidir. Impede que uma falha isolada
             * num servico recem-subido abra o disjuntor com amostra de tamanho
             * um — que e exatamente o cenario de um Solr frio.
             */
            int minimoDeChamadas,

            /* Falhas na janela que abrem o disjuntor. */
            int limiteDeFalhas,

            /* Quanto tempo fica aberto antes de liberar sondas. */
            long esperaMs,

            /*
             * Quantas chamadas de prova passam no meio-aberto. Poucas: o Solr
             * volta frio, e devolver de uma vez toda a carga que estava sendo
             * recusada e a forma mais comum de derruba-lo outra vez.
             */
            int sondas) {

        public Disjuntor {
            janela = janela <= 0 ? 20 : janela;
            minimoDeChamadas = minimoDeChamadas <= 0 ? 10 : minimoDeChamadas;
            limiteDeFalhas = limiteDeFalhas <= 0 ? 10 : limiteDeFalhas;
            esperaMs = esperaMs <= 0 ? 5000 : esperaMs;
            sondas = sondas <= 0 ? 2 : sondas;
        }

        public static Disjuntor padrao() {
            return new Disjuntor(true, 20, 10, 10, 5000, 2);
        }
    }

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

            boolean gatilhoManual,

            /*
             * Como clique e adesao viram afinidade. Ver PesosDeAfinidade: os
             * tres numeros substituem duas decisoes que, juntas, faziam o
             * sistema acreditar no primeiro clique e nunca esquecer.
             */
            Afinidade afinidade) { }

    /**
     * Janela, meia-vida e piso de confiança da afinidade.
     *
     * <p>A janela e a meia-vida fazem trabalhos diferentes, e confundi-los é o
     * erro fácil. A <strong>meia-vida</strong> decide a velocidade do
     * esquecimento. A <strong>janela</strong> só decide quando parar de somar, e
     * é generosa porque interesse em crédito imobiliário não se demonstra todo
     * dia.
     *
     * <p>O <strong>piso de confiança</strong> é quantos pontos valem afinidade
     * máxima. Com clique valendo 2 e adesão 5, seis pontos são três cliques de
     * hoje. Baixá-lo faz o sistema acreditar mais rápido; zerá-lo faz um clique
     * valer o máximo, que era o defeito.
     */
    public record Afinidade(int janelaDias, int meiaVidaDias, double pisoDeConfianca) {

        public Afinidade {
            janelaDias = janelaDias <= 0 ? 90 : janelaDias;
            meiaVidaDias = meiaVidaDias <= 0 ? 7 : meiaVidaDias;
            pisoDeConfianca = pisoDeConfianca <= 0 ? 6.0 : pisoDeConfianca;
        }

        public static Afinidade padrao() {
            return new Afinidade(90, 7, 6.0);
        }
    }
}
