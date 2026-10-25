package dev.sugestao.busca.adaptador.saida.agregado;

import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDeAgregados;
import dev.sugestao.busca.dominio.Jornada;
import dev.sugestao.busca.dominio.Normalizacao;
import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;
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

        var resultado = new ArrayList<TermoAgregado>(porTermo.size());
        porTermo.forEach((chave, a) -> resultado.add(a.fechar(chave)));
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
    public List<ComportamentoDoCliente> comportamentos(Duration janela, int tamanhoDoHistorico) {
        Instant corte = Instant.now().minus(janela);

        // LinkedHashSet para deduplicar mantendo a ordem, e a iteração é do
        // mais recente para o mais antigo: quem repete uma busca mantém a
        // posição da vez mais recente, não da primeira.
        Map<String, LinkedHashSet<String>> historicos = new HashMap<>();
        Map<String, Map<String, Double>> afinidades = new HashMap<>();

        var reverso = eventos.descendingIterator();
        while (reverso.hasNext()) {
            EventoDeBusca evento = reverso.next();
            if (evento.ocorridoEm().isBefore(corte)) {
                continue;
            }

            switch (evento.tipo()) {
                case BUSCA_DIGITADA -> historicos
                        .computeIfAbsent(evento.clienteId(), c -> new LinkedHashSet<>())
                        .add(evento.termo());

                case SUGESTAO_CLICADA -> somarAfinidade(
                        afinidades, evento.clienteId(), evento.jornada(), PESO_DO_CLIQUE);

                case ADESAO -> somarAfinidade(
                        afinidades, evento.clienteId(), evento.jornada(), PESO_DA_ADESAO);

                default -> { }
            }
        }

        var clientes = new LinkedHashSet<String>(historicos.keySet());
        clientes.addAll(afinidades.keySet());

        var resultado = new ArrayList<ComportamentoDoCliente>(clientes.size());
        for (String cliente : clientes) {
            var termos = historicos.getOrDefault(cliente, new LinkedHashSet<>());
            resultado.add(new ComportamentoDoCliente(
                    cliente,
                    termos.stream().limit(tamanhoDoHistorico).toList(),
                    // Gatilho não nasce de busca. Pix recusado, fatura alta e
                    // acesso suspeito vêm de outros produtores, que este POC não
                    // modela — por isso esta lista sai vazia e o escritor de
                    // perfil preserva os gatilhos que já existiam.
                    Set.of(),
                    normalizada(afinidades.get(cliente))));
        }
        return resultado;
    }

    private static void somarAfinidade(
            Map<String, Map<String, Double>> afinidades, String cliente, String jornada, double peso) {

        // Normaliza pela mesma função que normaliza a jornada no cadastro de
        // oferta. Sem isto, um app que manda "Crédito" cria um balde que nunca
        // casa com o `jornada_s` do índice, que é "credito": a afinidade é
        // contada, guardada, e não produz impulso nenhum. Falha silenciosa, do
        // tipo que se descobre meses depois perguntando por que o ranking por
        // comportamento não mexe em nada.
        String chave = Normalizacao.aplicar(jornada);

        // E o sentinela de "sem jornada" não acumula.
        //
        // O Rankeador barra `busca` da eleição de jornada líder de propósito —
        // ela é o balde dos termos do log e do histórico, e deixá-la concorrer
        // faria dela a líder quase sempre, só por ser a mais numerosa. Mas a
        // afinidade era aplicada por jornada do candidato, inclusive para esse
        // balde: três cliques em linha de histórico davam afinidade 1,0 com
        // `busca`, e aí TODA linha vinda do log passava a ser promovida. O sinal
        // mais fraco do conjunto virava o mais forte, pela porta de trás.
        if (Jornada.ausente(chave)) {
            return;
        }

        afinidades.computeIfAbsent(cliente, c -> new HashMap<>())
                .merge(chave, peso, Double::sum);
    }

    /**
     * Divide pela jornada mais forte da própria pessoa, não por um máximo
     * global. Afinidade é relativa a ela mesma: quem só mexeu em crédito tem
     * afinidade 1 com crédito, ainda que tenha clicado três vezes e outro
     * cliente trezentas. Normalizar contra a população transformaria esta
     * medida num segundo índice de popularidade, que já existe no {@code ctr}.
     */
    private static Map<String, Double> normalizada(Map<String, Double> brutos) {
        if (brutos == null || brutos.isEmpty()) {
            return Map.of();
        }
        double maior = brutos.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (maior <= 0) {
            return Map.of();
        }

        var escalada = new HashMap<String, Double>(brutos.size());
        brutos.forEach((jornada, peso) -> escalada.put(jornada, peso / maior));
        return Map.copyOf(escalada);
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
