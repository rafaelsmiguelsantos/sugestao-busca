package dev.sugestao.busca.adaptador.saida.agregado;

import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDeAgregados;
import dev.sugestao.busca.dominio.Normalizacao;
import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;
import dev.sugestao.busca.dominio.agregado.TermoAgregado;
import dev.sugestao.busca.dominio.evento.EventoDeBusca;
import dev.sugestao.busca.dominio.evento.TipoDeEvento;

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

    @Override
    public List<ComportamentoDoCliente> comportamentos(Duration janela, int tamanhoDoHistorico) {
        Instant corte = Instant.now().minus(janela);

        // LinkedHashSet para deduplicar mantendo a ordem, e a iteração é do
        // mais recente para o mais antigo: quem repete uma busca mantém a
        // posição da vez mais recente, não da primeira.
        Map<String, LinkedHashSet<String>> historicos = new HashMap<>();

        var reverso = eventos.descendingIterator();
        while (reverso.hasNext()) {
            EventoDeBusca evento = reverso.next();
            if (evento.ocorridoEm().isBefore(corte) || evento.tipo() != TipoDeEvento.BUSCA_DIGITADA) {
                continue;
            }
            historicos.computeIfAbsent(evento.clienteId(), c -> new LinkedHashSet<>())
                    .add(evento.termo());
        }

        var resultado = new ArrayList<ComportamentoDoCliente>(historicos.size());
        historicos.forEach((cliente, termos) -> resultado.add(new ComportamentoDoCliente(
                cliente,
                termos.stream().limit(tamanhoDoHistorico).toList(),
                // Gatilho não nasce de busca. Pix recusado, fatura alta e
                // acesso suspeito vêm de outros produtores, que este POC não
                // modela — por isso esta lista sai vazia e o escritor de perfil
                // preserva os gatilhos que já existiam.
                Set.of())));
        return resultado;
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
