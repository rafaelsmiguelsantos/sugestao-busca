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
        Seguranca seguranca,

        /* Tolerancia a erro de digitacao. Ver ConsultaDeCandidatos. */
        Busca busca,

        /* Medicao da qualidade da busca. Ver MedidorDeBusca. */
        Medicao medicao,

        /* Linha substituta quando a peneira zera a lista. Ver Recuperador. */
        Recuperacao recuperacao,

        /* Cache do perfil por sessao de digitacao. Ver PerfisComCache. */
        CacheDePerfil cacheDePerfil,

        /* Limite de taxa na rota de digitacao. Ver LimitadorDeTaxa. */
        LimiteDeTaxa limiteDeTaxa) {

    /**
     * A tolerância a erro de digitação.
     *
     * <p>Um interruptor só, e de propósito: os dois números que governam a
     * tolerância — comprimento mínimo e distância por tamanho — são decisões de
     * relevância que se discutem lendo {@code ConsultaDeCandidatos}, não valores
     * para alguém girar em produção. Expor um "distância máxima" configurável
     * convidaria a subi-lo até a busca passar a encontrar qualquer coisa, o que é
     * exatamente o modo de falha desta funcionalidade.
     */
    public record Busca(boolean toleranciaHabilitada) {

        public static Busca padrao() {
            return new Busca(true);
        }
    }

    /**
     * Liga a medição da qualidade da busca.
     *
     * <p>Ligada por padrão, porque o custo é um contador em memória e o valor é
     * a única evidência de que as mudanças de relevância fizeram algo. Desligar
     * troca o adaptador pelo {@code MedidorDeBusca.NULO} — não por um medidor que
     * finge medir.
     */
    public record Medicao(boolean habilitada) {

        public static Medicao padrao() {
            return new Medicao(true);
        }
    }

    /**
     * A linha que resgata a lista vazia, e as jornadas que a declararam.
     *
     * <p>O interruptor e o conteúdo são campos separados, e nenhum dos dois é
     * suficiente sozinho. Ligado com lista vazia não faz nada — e isso é de
     * propósito: o gate de verdade é o conteúdo, porque declarar a linha de uma
     * jornada é decisão de quem conhece a jornada, não de quem edita
     * configuração. O interruptor existe para desligar o recurso inteiro num
     * incidente sem ter de apagar o conteúdo e lembrar de repô-lo depois.
     *
     * <p><strong>{@code Boolean} e não {@code boolean}, pela mesma razão da
     * decisão 6.</strong> Com o primitivo, ausente e {@code false} são
     * indistinguíveis — e quem declarasse {@code linhas[0]} sem lembrar do
     * interruptor teria o recurso <em>desligado em silêncio</em>, com o conteúdo
     * no arquivo e nenhuma linha na tela. É o pior modo de falha de
     * configuração: parece configurado e não está. Ausente aqui significa "não
     * opinei", e a opinião padrão é ligado, igual ao disjuntor e à tolerância.
     *
     * <p><strong>Por que o conteúdo mora aqui e não no índice.</strong> Ele é
     * lido no caminho de 30 ms, exatamente quando a busca já falhou, e por isso
     * precisa estar em memória — ver {@code Substitutos}, que explica por que
     * isto não é uma porta. Em produção o carregamento vem do catálogo na subida;
     * o que não muda é que nada aqui vai à rede durante a requisição.
     */
    public record Recuperacao(Boolean habilitada, List<LinhaSubstituta> linhas) {

        public Recuperacao {
            linhas = linhas == null ? List.of() : List.copyOf(linhas);
            habilitada = habilitada == null ? Boolean.TRUE : habilitada;
        }

        public static Recuperacao padrao() {
            // Ligada e sem conteúdo: o comportamento é idêntico ao de antes do
            // recurso, e quem quiser o recurso declara as linhas.
            return new Recuperacao(Boolean.TRUE, List.of());
        }

        /** Nunca nulo: o construtor compacto resolve a ausência. */
        public boolean ligada() {
            return Boolean.TRUE.equals(habilitada);
        }
    }

    /**
     * Uma linha substituta declarada em configuração.
     *
     * <p>É um tipo de borda, espelho de {@code dominio.Substituto}: ele conhece o
     * formato do arquivo, e o do domínio conhece as regras. A validação — jornada
     * presente, termo presente, destino relativo — mora no domínio, e por isso
     * configuração inválida derruba a subida em vez de produzir uma linha quebrada
     * na primeira busca que falhar.
     */
    public record LinhaSubstituta(String jornada, String termo, String rotulo, String destino) { }

    /**
     * O cache do perfil por sessão de digitação.
     *
     * <p>Os três números têm papéis diferentes e o do meio é o único que muda
     * comportamento observável. Ver {@code PerfisComCache} para o raciocínio
     * inteiro — incluindo por que a invalidação por escrita foi recusada.
     */
    public record CacheDePerfil(boolean habilitado, long ttlMs, int tetoDeChaves) {

        /**
         * O teto do TTL, e ele é código em vez de comentário.
         *
         * <p>Trinta segundos. Acima disso o objeto deixa de ser "o perfil
         * enquanto a pessoa digita" e passa a ser "o perfil", o que é outra
         * decisão com outros riscos: a rodada de agregação roda a cada 15 s, e um
         * TTL de minutos a torna invisível — o sistema continuaria aprendendo e
         * pararia de usar o que aprendeu, sem erro em lugar nenhum.
         *
         * <p>Recusar na subida em vez de documentar é a diferença entre um limite
         * e um conselho.
         */
        private static final long TTL_MAXIMO_MS = 30_000;

        public CacheDePerfil {
            ttlMs = ttlMs <= 0 ? 5_000 : ttlMs;
            tetoDeChaves = tetoDeChaves <= 0 ? 50_000 : tetoDeChaves;

            if (ttlMs > TTL_MAXIMO_MS) {
                throw new IllegalArgumentException(
                        "sugestao.cache-de-perfil.ttl-ms=" + ttlMs + " passa do teto de "
                                + TTL_MAXIMO_MS + " ms. Acima disto o cache deixa de cobrir uma "
                                + "sessao de digitacao e passa a esconder a agregacao, que roda "
                                + "a cada 15 s.");
            }
        }

        /**
         * Desligado, igual ao {@code application.properties} — e os dois têm de
         * concordar.
         *
         * <p>Este método é o caminho de quem não tem a seção no arquivo. Se ele
         * devolvesse "ligado", uma configuração incompleta teria o cache ativo
         * enquanto a documentação afirma o contrário, e o default declarado
         * passaria a depender de o arquivo estar completo. Ver decisão 27 para
         * por que o desligado é o lado seguro aqui.
         */
        public static CacheDePerfil padrao() {
            return new CacheDePerfil(false, 5_000, 50_000);
        }
    }

    /**
     * Quantas requisições por minuto cada chamador pode fazer na rota de
     * sugestões.
     *
     * <p><strong>O número é alto de propósito, e isso é o ponto difícil
     * deste parâmetro.</strong> Uma caixa de busca legitimamente faz uma
     * requisição por tecla: quem digita "financiamento imobiliário" rápido gasta
     * 25 em poucos segundos. Um limite apertado o bastante para incomodar quem
     * raspa o catálogo quebra quem digita; um limite folgado o bastante para
     * digitação não impede quem raspa com paciência. Os 600/min são calibrados
     * para o primeiro — deixar a pessoa digitar — e o segundo problema não se
     * resolve aqui.
     *
     * <p>É defesa em profundidade, não a camada principal. A camada principal é
     * o gateway, que vê todas as instâncias e sobrevive a restart; este contador
     * é por processo e em memória. Ver {@code LimitadorDeTaxa}.
     */
    public record LimiteDeTaxa(boolean habilitado, int porMinuto, int tetoDeChaves) {

        public LimiteDeTaxa {
            porMinuto = porMinuto <= 0 ? 600 : porMinuto;
            // Teto de chaves rastreadas. Sem ele, o próprio limitador é o vetor:
            // um atacante com tokens descartáveis faria o mapa crescer até o
            // processo cair, usando a defesa como ataque.
            tetoDeChaves = tetoDeChaves <= 0 ? 50_000 : tetoDeChaves;
        }

        public static LimiteDeTaxa padrao() {
            return new LimiteDeTaxa(true, 600, 50_000);
        }
    }

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

            /* Teto da afinidade por PRODUTO. Menor que o da jornada. */
            double afinidadeDeProdutoMaxima,

            /* Teto da tendencia. O menor dos quatro: e o sinal de menos confianca. */
            double tendenciaMaxima,

            /*
             * Teto do PRODUTO de todos os fatores de promocao, declarado em vez
             * de emergente. Antes ele era 3,0 porque 1,6 x 1,25 x 1,5 da 3,0, e
             * esse numero nao estava escrito em lugar nenhum — acrescentar um
             * fator mudava o teto do sistema em silencio.
             */
            double fatorMaximo,

            /*
             * Piso do fator de fadiga. O unico peso abaixo de 1, e o unico que
             * rebaixa. Em 0 ele removeria a linha sem dizer por que, que e
             * trabalho da Peneira.
             */
            double fadigaMinima,

            double margemParaLiderar) {

        public Peso {
            // Os cinco novos ganham padrao aqui, e nao no application.properties,
            // para que uma configuracao antiga continue subindo. Sem isto, quem
            // tiver o arquivo da versao anterior ve a aplicacao morrer na
            // validacao de PesosDeRanking com zero em tres campos.
            afinidadeDeProdutoMaxima = afinidadeDeProdutoMaxima < 1.0 ? 1.2 : afinidadeDeProdutoMaxima;
            tendenciaMaxima = tendenciaMaxima < 1.0 ? 1.15 : tendenciaMaxima;
            fatorMaximo = fatorMaximo < 1.0 ? 3.0 : fatorMaximo;
            fadigaMinima = fadigaMinima <= 0 || fadigaMinima > 1.0 ? 0.6 : fadigaMinima;
        }
    }

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
            Afinidade afinidade,

            /*
             * O sinal negativo: exibir e nao clicar. Ver PesosDeFadiga para as
             * tres defesas (piso de evidencia, teto e janela) e para o que ele
             * deliberadamente NAO corrige — vies de posicao.
             */
            Fadiga fadiga) { }

    /**
     * Quando uma linha exibida e ignorada passa a valer menos.
     *
     * <p>O <strong>piso de evidência</strong> é o número que mais importa aqui, e
     * baixá-lo tem um efeito específico e ruim: oferta nova passa a ser penalizada
     * antes de ter tido chance de receber clique, afunda, e nunca recebe. É o
     * mesmo laço que o piso de confiança da afinidade corrige na direção oposta.
     */
    public record Fadiga(
            int impressoesMinimas,
            int impressoesParaOTeto,
            int janelaDias,

            /*
             * Teto de termos cansados por pessoa. Existe por causa do caminho de
             * LEITURA: o perfil e lido a cada tecla, e a fadiga e o unico sinal
             * dele indexado por termo. Ver PesosDeFadiga.tamanhoMaximo.
             */
            int tamanhoMaximo) {

        public Fadiga {
            impressoesMinimas = impressoesMinimas <= 0 ? 5 : impressoesMinimas;
            impressoesParaOTeto = impressoesParaOTeto <= impressoesMinimas
                    ? impressoesMinimas * 4
                    : impressoesParaOTeto;
            janelaDias = janelaDias <= 0 ? 30 : janelaDias;
            tamanhoMaximo = tamanhoMaximo <= 0 ? 50 : tamanhoMaximo;
        }

        public static Fadiga padrao() {
            return new Fadiga(5, 20, 30, 50);
        }
    }

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
    public record Afinidade(
            int janelaDias,
            int meiaVidaDias,
            double pisoDeConfianca,

            /*
             * O piso do nivel do PRODUTO, mais baixo que o da jornada. Evidencia
             * por produto e escassa — ha meia duzia de jornadas e vinte e tantos
             * produtos — e um piso igual faria a afinidade por produto ficar
             * correta e sempre zero. Ver PesosDeAfinidade.
             */
            double pisoDeConfiancaDoProduto,

            /* As duas janelas da tendencia, em dias. */
            int tendenciaCurtaDias,
            int tendenciaLongaDias,

            /*
             * Pontos minimos na janela longa para a tendencia ser calculada.
             * Abaixo disto ela devolve neutro, porque razao de numeros pequenos
             * e ruido — e um sinal de ruido e pior que sinal nenhum.
             */
            double pisoDaTendencia) {

        public Afinidade {
            janelaDias = janelaDias <= 0 ? 90 : janelaDias;
            meiaVidaDias = meiaVidaDias <= 0 ? 7 : meiaVidaDias;
            pisoDeConfianca = pisoDeConfianca <= 0 ? 6.0 : pisoDeConfianca;
            pisoDeConfiancaDoProduto = pisoDeConfiancaDoProduto <= 0
                    ? 4.0
                    : pisoDeConfiancaDoProduto;
            tendenciaCurtaDias = tendenciaCurtaDias <= 0 ? 7 : tendenciaCurtaDias;
            tendenciaLongaDias = tendenciaLongaDias <= tendenciaCurtaDias
                    ? tendenciaCurtaDias * 4
                    : tendenciaLongaDias;
            pisoDaTendencia = pisoDaTendencia <= 0 ? 4.0 : pisoDaTendencia;
        }

        public static Afinidade padrao() {
            return new Afinidade(90, 7, 6.0, 4.0, 7, 30, 4.0);
        }
    }
}
