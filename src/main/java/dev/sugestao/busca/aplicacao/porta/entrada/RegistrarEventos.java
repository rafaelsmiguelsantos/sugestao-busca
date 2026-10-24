package dev.sugestao.busca.aplicacao.porta.entrada;

import dev.sugestao.busca.dominio.evento.EventoDeBusca;

import java.util.List;

/**
 * A porta de entrada do caminho de escrita: a única por onde evento entra.
 *
 * <p>"Única porta" é a palavra do desenho e vale a pena levar a sério. Se
 * houvesse dois caminhos de ingestão, o saneamento teria que existir nos dois —
 * e bastaria um esquecer para que o portão deixasse de valer.
 */
public interface RegistrarEventos {

    ResultadoDaIngestao executar(List<EventoDeBusca> eventos);
}
