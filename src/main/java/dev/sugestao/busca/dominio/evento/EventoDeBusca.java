package dev.sugestao.busca.dominio.evento;

import dev.sugestao.busca.dominio.Segmento;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * O que o app manda de volta depois de uma lista mostrada.
 *
 * <p>Carrega {@code clienteId} porque a agregação precisa contar <em>clientes
 * distintos</em>, não sessões — um termo que uma pessoa digitou cinquenta vezes
 * continua sendo dado de uma pessoa só. É esse identificador que o piso de
 * k-anonimato conta, e é por isso que ele atravessa a ingestão. Ele morre na
 * agregação: o que é publicado no índice é o termo e um número, nunca a lista de
 * quem o digitou.
 *
 * <p>{@code posicao} só faz sentido para impressão e clique, e vale -1 no resto.
 */
public record EventoDeBusca(
        TipoDeEvento tipo,
        String clienteId,
        Segmento segmento,
        String termo,
        List<String> termosExibidos,
        int posicao,
        String produto,
        String jornada,
        boolean listaDegradada,
        Instant ocorridoEm) {

    public EventoDeBusca {
        Objects.requireNonNull(tipo, "tipo do evento");
        termosExibidos = termosExibidos == null ? List.of() : List.copyOf(termosExibidos);
        termo = termo == null ? "" : termo;
        produto = produto == null ? "" : produto;
        jornada = jornada == null ? "" : jornada;
        ocorridoEm = ocorridoEm == null ? Instant.now() : ocorridoEm;
        segmento = segmento == null ? Segmento.PF : segmento;
    }

    public boolean temCliente() {
        return clienteId != null && !clienteId.isBlank();
    }
}
