package dev.sugestao.busca.adaptador.saida.agregado;

import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDeAgregados;
import dev.sugestao.busca.dominio.Jornada;
import dev.sugestao.busca.dominio.Normalizacao;
import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;
import dev.sugestao.busca.dominio.agregado.PesosDeAfinidade;
import dev.sugestao.busca.dominio.agregado.PesosDeFadiga;
import dev.sugestao.busca.dominio.agregado.TermoAgregado;
import dev.sugestao.busca.dominio.evento.EventoDeBusca;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleUnaryOperator;

/**
 * O lugar do job de agregação em janelas.
 *
 * <p>Guarda os eventos crus e recalcula a janela a cada rodada, em vez de manter
 * contadores incrementais. Para o volume de um POC isso é mais barato de ler e
 * tem uma propriedade que o contador incremental não tem de graça: a janela
 * realmente desliza. Um termo que parou de ser digitado cai sozinho, e um
 * gatilho de 24 h expira sem ninguém precisar lembrar de expirá-lo.
 *
 * <p>Num volume real isto não se sustenta — é o ponto em que entra um job de
 * janela deslizante de verdade, e é por isso que a agregação está atrás de uma
 * porta em vez de embutida no caso de uso.
 *
 * <p>A chave de agregação é o termo <em>normalizado</em>, a mesma de
 * {@code Normalizacao}. Se fosse o texto cru, "Pagar Boleto" e "pagar boleto"
 * contariam como dois termos — e cada um com metade dos clientes distintos, o
 * que faria os dois baterem no piso de k-anonimato e sumirem.
 */
public class AgregadosEmMemoria implements RepositorioDeAgregados {

    private final ConcurrentLinkedDeque<EventoDeBusca> eventos = new ConcurrentLinkedDeque<>();
    private final AtomicInteger retidos = new AtomicInteger();
    private final int teto;

    public AgregadosEmMemoria(int teto) {
        this.teto = teto;
    }

    @Override
    public void acumular(List<EventoDeBusca> novos) {
        for (EventoDeBusca evento : novos) {
            eventos.addLast(evento);
            if (retidos.incrementAndGet() > teto && eventos.pollFirst() != null) {
                retidos.decrementAndGet();
            }
        }
    }

    @Override
    public List<TermoAgregado> termos(Duration janela) {
        Instant corte = Instant.now().minus(janela);
        Map<String, Acumulador> porTermo = new HashMap<>();

        for (EventoDeBusca evento : eventos) {
            if (evento.ocorridoEm().isBefore(corte)) {
                continue;
            }
            switch (evento.tipo()) {
                case BUSCA_DIGITADA -> acumulador(porTermo, evento.termo())
                        .busca(evento.clienteId(), evento.segmento().name(), evento.ocorridoEm());

                // A impressão conta para cada termo que apareceu na tela, e é
                // ela que dá denominador ao CTR. Sem isto o clique seria
                // dividido por busca, que mede outra coisa.
                case SUGESTAO_EXIBIDA -> {
                    for (String exibido : evento.termosExibidos()) {
                        acumulador(porTermo, exibido).exibicao();
                    }
                }
                case SUGESTAO_CLICADA -> acumulador(porTermo, evento.termo()).clique();
                case ADESAO -> acumulador(porTermo, evento.termo()).adesao();
            }
        }

        // Termo vazio nunca vira vocabulário.
        //
        // Apareceu por um caminho e vale por dois. O caminho: os eventos
        // semeados da demonstração são cliques com termo vazio — o que importa
        // neles é a jornada — e criavam um agregado de chave "" que o relatório
        // reportava como termo retido pelo piso. O valor: um cliente pode mandar
        // evento cujo termo o saneamento reduz a nada, e sem este corte isso
        // seria uma entrada de vocabulário que nenhuma busca alcança e que o
        // portão de publicação nem examina, porque ele ignora chave com menos de
        // dois caracteres.
        //
        // Aqui e não na ingestão porque a ingestão aceita de propósito o evento
        // cujo termo não interessa: SUGESTAO_CLICADA vale pela jornada, e
        // recusá-lo por não ter termo jogaria fora o sinal de afinidade junto.
        var resultado = new ArrayList<TermoAgregado>(porTermo.size());
        porTermo.forEach((chave, a) -> {
            if (!chave.isBlank()) {
                resultado.add(a.fechar(chave));
            }
        });
        return resultado;
    }

    /**
     * Clicar pesa, contratar pesa mais, buscar não pesa.
     *
     * <p>Busca é declaração de dúvida e já está representada no histórico;
     * transformá-la em afinidade faria quem procura entender um produto ser
     * tratado como quem o quer. Contratação vale mais que clique porque é o
     * único destes sinais que o negócio reconhece como resultado.
     */
    private static final double PESO_DO_CLIQUE = 2.0;
    private static final double PESO_DA_ADESAO = 5.0;

    @Override
    public List<ComportamentoDoCliente> comportamentos(
            Duration janelaDoHistorico, int tamanhoDoHistorico,
            PesosDeAfinidade pesos, PesosDeFadiga fadiga) {

        Instant agora = Instant.now();
        Instant corteDoHistorico = agora.minus(janelaDoHistorico);
        Instant corteDaAfinidade = pesos.corte(agora);
        Instant corteCurto = pesos.corteCurto(agora);
        Instant corteLongo = pesos.corteLongo(agora);
        Instant corteDaFadiga = fadiga.corte(agora);

        // As janelas são várias e a varredura é uma só: a mais larga manda, e
        // cada acumulador aplica o próprio corte dentro do laço. Varrer a fila
        // uma vez por janela seria quatro passadas sobre dezenas de milhares de
        // eventos a cada 15 segundos.
        Instant corteMaisLargo = maisAntigo(
                corteDoHistorico, corteDaAfinidade, corteLongo, corteDaFadiga);

        // LinkedHashSet para deduplicar mantendo a ordem, e a iteração é do
        // mais recente para o mais antigo: quem repete uma busca mantém a
        // posição da vez mais recente, não da primeira.
        Map<String, LinkedHashSet<String>> historicos = new HashMap<>();

        // Pontos BRUTOS, já descontada a idade de cada evento. Viram afinidade
        // entre 0 e 1 no fim, dividindo pelo piso de confiança — não pelo máximo
        // da própria pessoa, que era o que fazia um clique valer 1,0.
        Map<String, Map<String, Double>> pontosPorJornada = new HashMap<>();
        Map<String, Map<String, Double>> pontosPorProduto = new HashMap<>();

        // As duas janelas da tendência. Sem decaimento: a tendência é razão
        // entre volumes de dois períodos, e aplicar meia-vida aqui contaria o
        // tempo duas vezes — uma na janela, outra no decaimento — fazendo toda
        // tendência parecer de alta.
        Map<String, Map<String, Double>> pontosCurta = new HashMap<>();
        Map<String, Map<String, Double>> pontosLonga = new HashMap<>();

        // Impressões e cliques por (cliente, termo), para a fadiga.
        Map<String, Map<String, int[]>> exposicoes = new HashMap<>();

        var reverso = eventos.descendingIterator();
        while (reverso.hasNext()) {
            EventoDeBusca evento = reverso.next();

            // A fila é cronológica e a varredura é do fim para o começo: o
            // primeiro evento velho encontrado encerra a varredura, porque todos
            // os anteriores são mais velhos ainda. `continue` percorreria a fila
            // inteira a cada rodada, e ela guarda dezenas de milhares.
            if (evento.ocorridoEm().isBefore(corteMaisLargo)) {
                break;
            }

            switch (evento.tipo()) {
                case BUSCA_DIGITADA -> {
                    if (!evento.ocorridoEm().isBefore(corteDoHistorico)) {
                        historicos.computeIfAbsent(evento.clienteId(), c -> new LinkedHashSet<>())
                                .add(evento.termo());
                    }
                }

                case SUGESTAO_EXIBIDA -> {
                    if (!evento.ocorridoEm().isBefore(corteDaFadiga)) {
                        for (String exibido : evento.termosExibidos()) {
                            contador(exposicoes, evento.clienteId(), exibido)[0]++;
                        }
                    }
                }

                case SUGESTAO_CLICADA -> {
                    somar(pontosPorJornada, pontosPorProduto, pontosCurta, pontosLonga,
                            evento, PESO_DO_CLIQUE, pesos, agora,
                            corteDaAfinidade, corteCurto, corteLongo);

                    if (!evento.ocorridoEm().isBefore(corteDaFadiga)) {
                        contador(exposicoes, evento.clienteId(), evento.termo())[1]++;
                    }
                }

                case ADESAO -> somar(pontosPorJornada, pontosPorProduto, pontosCurta, pontosLonga,
                        evento, PESO_DA_ADESAO, pesos, agora,
                        corteDaAfinidade, corteCurto, corteLongo);

                default -> { }
            }
        }

        var clientes = new LinkedHashSet<String>(historicos.keySet());
        clientes.addAll(pontosPorJornada.keySet());
        clientes.addAll(exposicoes.keySet());

        var resultado = new ArrayList<ComportamentoDoCliente>(clientes.size());
        for (String cliente : clientes) {
            var termos = historicos.getOrDefault(cliente, new LinkedHashSet<>());

            var comportamento = new ComportamentoDoCliente(
                    cliente,
                    termos.stream().limit(tamanhoDoHistorico).toList(),
                    // Gatilho não nasce de busca. Pix recusado, fatura alta e
                    // acesso suspeito vêm de outros produtores, que este POC não
                    // modela — por isso esta lista sai vazia e o escritor de
                    // perfil preserva os gatilhos que já existiam.
                    Set.of(),
                    comoAfinidade(pontosPorJornada.get(cliente), pesos::afinidadeDe),
                    comoAfinidade(pontosPorProduto.get(cliente), pesos::afinidadeDeProduto),
                    comoTendencia(pontosCurta.get(cliente), pontosLonga.get(cliente), pesos),
                    comoFadiga(exposicoes.get(cliente), fadiga));

            // Comportamento sem nada a dizer não é devolvido, e isto não é
            // economia: é correção de um efeito que a fadiga introduziu.
            //
            // O conjunto de clientes é semeado também por `exposicoes`, que
            // precisa dos CLIQUES para poder zerar a fadiga de um termo. O efeito
            // colateral é que um cliente cujos cliques foram todos em linha sem
            // jornada — histórico e do log, que não geram afinidade — passava a
            // aparecer aqui com as quatro dimensões vazias. O escritor de perfil
            // criaria um perfil para ele com `conhecido = true`, e a lista dessa
            // pessoa deixaria de ser marcada como degradada sem que uma única
            // informação sobre ela tivesse sido aprendida.
            //
            // Degradada é o que ela é: não há perfil, só um clique que não
            // ensinou nada.
            if (temAlgoADizer(comportamento)) {
                resultado.add(comportamento);
            }
        }
        return resultado;
    }

    private static boolean temAlgoADizer(ComportamentoDoCliente c) {
        return !c.historico().isEmpty()
                || !c.afinidadePorJornada().isEmpty()
                || !c.afinidadePorProduto().isEmpty()
                || !c.tendenciaPorJornada().isEmpty()
                || !c.fadigaPorTermo().isEmpty();
    }

    private static Instant maisAntigo(Instant... cortes) {
        Instant menor = cortes[0];
        for (Instant corte : cortes) {
            if (corte.isBefore(menor)) {
                menor = corte;
            }
        }
        return menor;
    }

    /**
     * {@code [0]} impressões, {@code [1]} cliques.
     *
     * <p>Um {@code int[2]} e não um record por decisão de alocação: este mapa
     * cresce com (clientes × termos exibidos) e é reconstruído inteiro a cada 15
     * segundos. Um objeto por par custaria caro exatamente no laço mais quente do
     * caminho de escrita, e o array é mutável no lugar.
     */
    private static int[] contador(
            Map<String, Map<String, int[]>> mapa, String cliente, String termo) {
        return mapa.computeIfAbsent(cliente, c -> new HashMap<>())
                .computeIfAbsent(Normalizacao.aplicar(termo), t -> new int[2]);
    }

    /**
     * Pontos brutos por jornada e por produto, mais as duas janelas da
     * tendência, num evento só.
     *
     * <p>Quatro mapas numa chamada porque é a mesma leitura do mesmo evento:
     * separar em quatro métodos custaria quatro normalizações do mesmo texto e
     * quatro buscas no mesmo mapa, no laço mais quente do caminho de escrita.
     */
    private static void somar(
            Map<String, Map<String, Double>> porJornada,
            Map<String, Map<String, Double>> porProduto,
            Map<String, Map<String, Double>> curta,
            Map<String, Map<String, Double>> longa,
            EventoDeBusca evento,
            double pesoBase,
            PesosDeAfinidade pesos,
            Instant agora,
            Instant corteDaAfinidade,
            Instant corteCurto,
            Instant corteLongo) {

        // Normaliza pela mesma função que normaliza a jornada no cadastro de
        // oferta. Sem isto, um app que manda "Crédito" cria um balde que nunca
        // casa com o `jornada_s` do índice, que é "credito": a afinidade é
        // contada, guardada, e não produz impulso nenhum. Falha silenciosa, do
        // tipo que se descobre meses depois perguntando por que o ranking por
        // comportamento não mexe em nada.
        String jornada = Normalizacao.aplicar(evento.jornada());

        // E o sentinela de "sem jornada" não acumula.
        //
        // O Rankeador barra `busca` da eleição de jornada líder de propósito —
        // ela é o balde dos termos do log e do histórico, e deixá-la concorrer
        // faria dela a líder quase sempre, só por ser a mais numerosa. Mas a
        // afinidade era aplicada por jornada do candidato, inclusive para esse
        // balde: três cliques em linha de histórico davam afinidade 1,0 com
        // `busca`, e aí TODA linha vinda do log passava a ser promovida. O sinal
        // mais fraco do conjunto virava o mais forte, pela porta de trás.
        if (Jornada.ausente(jornada)) {
            return;
        }

        if (!evento.ocorridoEm().isBefore(corteDaAfinidade)) {
            double peso = pesos.pesoComIdade(pesoBase, evento.ocorridoEm(), agora);

            acumular(porJornada, evento.clienteId(), jornada, peso);

            // O produto pode vir vazio: linha popular e linha de histórico não
            // têm produto. Nesse caso só o nível da jornada é contado — não um
            // balde de produto vazio, que seria o mesmo erro que `busca` era na
            // jornada.
            String produto = evento.produto();
            if (produto != null && !produto.isBlank()) {
                acumular(porProduto, evento.clienteId(), produto.strip(), peso);
            }
        }

        // As janelas da tendência contam o peso BASE, sem decaimento. Aplicar
        // meia-vida aqui contaria o tempo duas vezes — a janela já é um corte
        // temporal — e o efeito seria sistemático: a janela curta sofreria menos
        // decaimento que a longa, e toda tendência pareceria de alta.
        if (!evento.ocorridoEm().isBefore(corteLongo)) {
            acumular(longa, evento.clienteId(), jornada, pesoBase);

            if (!evento.ocorridoEm().isBefore(corteCurto)) {
                acumular(curta, evento.clienteId(), jornada, pesoBase);
            }
        }
    }

    private static void acumular(
            Map<String, Map<String, Double>> mapa, String cliente, String chave, double valor) {
        mapa.computeIfAbsent(cliente, c -> new HashMap<>()).merge(chave, valor, Double::sum);
    }

    /**
     * Pontos viram afinidade dividindo pelo <strong>piso de confiança</strong>,
     * que é constante — não pela jornada mais forte da própria pessoa.
     *
     * <p>A divisão pelo próprio máximo era a decisão anterior, e a intenção
     * estava certa: não transformar esta medida num segundo índice de
     * popularidade, que já existe no {@code ctr}. O efeito era outro. Com
     * {@code {credito: 2.0}}, o máximo da pessoa é 2,0 e o resultado é 1,0 —
     * <em>um clique dava afinidade máxima</em>, e um clique acidental punha a
     * pessoa no teto de promoção da jornada.
     *
     * <p>Com divisor constante, a propriedade que importava fica: três cliques e
     * trezentos cliques chegam ao mesmo 1,0, porque o valor é limitado em 1, e
     * nenhum dos dois é comparado com a população. O que muda é que pouca
     * evidência passa a valer pouco — que é o que pouca evidência é.
     *
     * <p>Chave abaixo de um milésimo é descartada. São os eventos tão velhos
     * que o decaimento já os tornou irrelevantes, e mantê-los no mapa só gasta
     * espaço no perfil e tempo na comparação.
     *
     * <p>Recebe a normalização como função para servir aos dois níveis: a
     * jornada divide pelo piso de 6,0 e o produto pelo de 4,0, e o resto da
     * conta é idêntico.
     */
    private static Map<String, Double> comoAfinidade(
            Map<String, Double> pontos, DoubleUnaryOperator normalizacao) {

        if (pontos == null || pontos.isEmpty()) {
            return Map.of();
        }

        var afinidades = new HashMap<String, Double>(pontos.size());
        pontos.forEach((chave, total) -> {
            double afinidade = normalizacao.applyAsDouble(total);
            if (afinidade >= 0.001) {
                afinidades.put(chave, afinidade);
            }
        });
        return Map.copyOf(afinidades);
    }

    /**
     * A tendência por jornada, só onde há evidência para ela.
     *
     * <p>Itera pela janela <strong>longa</strong>, que é o denominador: uma
     * jornada que só aparece na curta não existiria na longa, o que é impossível
     * porque a curta está contida na longa. Iterar pela curta deixaria de fora
     * justamente as jornadas que esfriaram — que são as que a tendência tem algo
     * a dizer sobre.
     *
     * <p>E só guarda o que difere de 1,0. O neutro é o padrão do lado que lê, em
     * {@code Perfil.tendenciaCom}, então gravar 1,0 explicitamente encheria o
     * perfil de entradas que não mudam nada — e o perfil é lido a cada tecla.
     */
    private static Map<String, Double> comoTendencia(
            Map<String, Double> curta, Map<String, Double> longa, PesosDeAfinidade pesos) {

        if (longa == null || longa.isEmpty()) {
            return Map.of();
        }

        var tendencias = new HashMap<String, Double>(longa.size());
        longa.forEach((jornada, totalLongo) -> {
            double totalCurto = curta == null ? 0.0 : curta.getOrDefault(jornada, 0.0);
            double tendencia = pesos.tendenciaDe(totalCurto, totalLongo);

            if (Math.abs(tendencia - 1.0) >= 0.01) {
                tendencias.put(jornada, tendencia);
            }
        });
        return Map.copyOf(tendencias);
    }

    /**
     * A fadiga por termo: impressões sem clique, acima do piso de evidência.
     *
     * <p>Só guarda o que é maior que zero, que é a minoria dos termos — a maioria
     * foi exibida poucas vezes ou foi clicada. Gravar zero para todos os termos
     * já exibidos faria o perfil crescer com o vocabulário em vez de com o
     * comportamento.
     */
    private static Map<String, Double> comoFadiga(
            Map<String, int[]> exposicoes, PesosDeFadiga pesos) {

        if (exposicoes == null || exposicoes.isEmpty()) {
            return Map.of();
        }

        var fadigas = new HashMap<String, Double>();
        exposicoes.forEach((termo, contagem) -> {
            if (termo.isBlank()) {
                return;
            }
            double fadiga = pesos.fadigaDe(contagem[0], contagem[1]);
            if (fadiga > 0) {
                fadigas.put(termo, fadiga);
            }
        });
        return Map.copyOf(fadigas);
    }

    @Override
    public int eventosRetidos() {
        return retidos.get();
    }

    private static Acumulador acumulador(Map<String, Acumulador> mapa, String termo) {
        return mapa.computeIfAbsent(Normalizacao.aplicar(termo), c -> new Acumulador());
    }

    /** Mutável de propósito: vive dentro de uma rodada e morre nela. */
    private static final class Acumulador {
        private final Set<String> clientes = new HashSet<>();
        private final Set<String> segmentos = new HashSet<>();
        private Instant vistoEm = Instant.EPOCH;
        private int frequencia;
        private int exibicoes;
        private int cliques;
        private int adesoes;

        void busca(String cliente, String segmento, Instant quando) {
            clientes.add(cliente);
            segmentos.add(segmento);
            frequencia++;
            if (quando.isAfter(vistoEm)) {
                vistoEm = quando;
            }
        }

        void exibicao() {
            exibicoes++;
        }

        void clique() {
            cliques++;
        }

        void adesao() {
            adesoes++;
        }

        /**
         * O termo publicado é a forma normalizada, não a redação de quem
         * digitou por último. Guardar a redação crua faria o vocabulário
         * carregar o jeito de escrever de uma pessoa específica — e, com
         * acento e maiúscula preservados, a chave de dedup do BFF deixaria de
         * bater com o que está no índice.
         */
        TermoAgregado fechar(String chave) {
            return new TermoAgregado(
                    chave,
                    segmentos,
                    clientes.size(),
                    frequencia,
                    exibicoes,
                    cliques,
                    adesoes,
                    vistoEm == Instant.EPOCH ? Instant.now() : vistoEm);
        }
    }
}
