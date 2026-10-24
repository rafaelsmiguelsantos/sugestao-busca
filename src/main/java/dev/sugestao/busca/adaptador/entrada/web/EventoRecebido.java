package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.dominio.Cliente;
import dev.sugestao.busca.dominio.evento.EventoDeBusca;
import dev.sugestao.busca.dominio.evento.TipoDeEvento;

import java.time.Instant;
import java.util.List;

/**
 * Um evento como o app manda.
 *
 * <p><strong>Não há {@code clienteId} aqui, e isso é a decisão central deste
 * arquivo.</strong> O identificador vem do token, pelo mesmo resolvedor que
 * atende a busca. Se ele viesse no corpo, qualquer cliente poderia atribuir
 * eventos a qualquer outro — e como é esse identificador que a agregação conta
 * para medir clientes distintos, quem controlasse o corpo controlaria o piso de
 * k-anonimato. Dava para fabricar trinta identificadores e publicar o que
 * quisesse como sugestão para todo mundo.
 */
public record EventoRecebido(
        String tipo,
        String termo,
        List<String> termosExibidos,
        Integer posicao,
        String produto,
        String jornada,
        Boolean listaDegradada,
        Instant ocorridoEm) {

    public EventoDeBusca paraDominio(Cliente cliente) {
        return new EventoDeBusca(
                TipoDeEvento.valueOf(tipo.trim().toUpperCase()),
                cliente.id(),
                cliente.segmento(),
                termo,
                termosExibidos,
                posicao == null ? -1 : posicao,
                produto,
                jornada,
                Boolean.TRUE.equals(listaDegradada),
                // Horário do servidor quando o app não manda o dele. Relógio de
                // aparelho erra, e um evento com data no futuro ficaria preso
                // na janela para sempre.
                ocorridoEm == null || ocorridoEm.isAfter(Instant.now()) ? Instant.now() : ocorridoEm);
    }
}
