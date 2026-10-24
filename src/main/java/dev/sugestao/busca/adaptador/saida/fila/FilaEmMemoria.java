package dev.sugestao.busca.adaptador.saida.fila;

import dev.sugestao.busca.aplicacao.porta.saida.FilaDeEventos;
import dev.sugestao.busca.dominio.evento.EventoDeBusca;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * O lugar do Amazon MSK enquanto o POC roda nesta máquina.
 *
 * <p>Não finge ser Kafka. Não tem partição, não tem reprocessamento, não
 * sobrevive a reinício, e um evento consumido está perdido se a agregação
 * falhar depois. É uma fila na memória de um processo só, e as três
 * propriedades que faltam são exatamente as que justificam existir um MSK no
 * desenho — vale mais dizer isso aqui do que simular mal.
 *
 * <p>O que ela preserva é a <em>forma</em>: publicar e consumir são as mesmas
 * duas chamadas que um produtor e um consumidor Kafka fariam. Trocar é escrever
 * outro adaptador, e nada acima desta classe muda.
 *
 * <p>O teto existe para que o POC não consuma a memória da máquina se ninguém
 * consumir. Descartar o evento mais antigo é a decisão certa aqui: evento de
 * busca perde valor rápido, e a alternativa seria derrubar a ingestão.
 */
public class FilaEmMemoria implements FilaDeEventos {

    private final ConcurrentLinkedQueue<EventoDeBusca> fila = new ConcurrentLinkedQueue<>();
    private final AtomicInteger tamanho = new AtomicInteger();
    private final AtomicInteger descartadosPorLotacao = new AtomicInteger();
    private final int teto;

    public FilaEmMemoria(int teto) {
        this.teto = teto;
    }

    @Override
    public void publicar(List<EventoDeBusca> eventos) {
        for (EventoDeBusca evento : eventos) {
            fila.add(evento);
            if (tamanho.incrementAndGet() > teto && fila.poll() != null) {
                tamanho.decrementAndGet();
                descartadosPorLotacao.incrementAndGet();
            }
        }
    }

    @Override
    public List<EventoDeBusca> consumir(int maximo) {
        var lote = new ArrayList<EventoDeBusca>(Math.min(maximo, tamanho.get()));

        for (int i = 0; i < maximo; i++) {
            EventoDeBusca evento = fila.poll();
            if (evento == null) {
                break;
            }
            tamanho.decrementAndGet();
            lote.add(evento);
        }
        return lote;
    }

    @Override
    public int pendentes() {
        return tamanho.get();
    }

    /** Quantos eventos a fila jogou fora por lotação. Acima de zero é alarme. */
    public int descartadosPorLotacao() {
        return descartadosPorLotacao.get();
    }
}
