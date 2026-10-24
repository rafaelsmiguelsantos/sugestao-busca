package dev.sugestao.busca.aplicacao.casodeuso;

import dev.sugestao.busca.aplicacao.porta.entrada.RegistrarEventos;
import dev.sugestao.busca.aplicacao.porta.entrada.ResultadoDaIngestao;
import dev.sugestao.busca.aplicacao.porta.saida.FilaDeEventos;
import dev.sugestao.busca.dominio.evento.EventoDeBusca;
import dev.sugestao.busca.dominio.evento.Saneador;
import dev.sugestao.busca.dominio.evento.Saneamento;
import dev.sugestao.busca.dominio.evento.TipoDeEvento;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Valida o esquema, passa pelo portão de saneamento e publica na fila.
 *
 * <p>É deliberadamente a etapa mais curta do caminho de escrita. Nada é agregado
 * aqui, nada é calculado, nada é consultado: o app está esperando um 202, e
 * qualquer trabalho feito neste ponto é trabalho feito com alguém esperando.
 *
 * <p>O saneamento, por outro lado, <em>precisa</em> estar aqui e não adiante.
 * Se ele ficasse na publicação, o termo com CPF teria sido gravado na fila e
 * nos agregados antes de ser recusado — e "o log bruto também não deveria
 * guardar" deixaria de ser verdade em dois lugares.
 */
public class RegistrarEventosCasoDeUso implements RegistrarEventos {

    private final FilaDeEventos fila;
    private final Saneador saneador;

    public RegistrarEventosCasoDeUso(FilaDeEventos fila, Saneador saneador) {
        this.fila = fila;
        this.saneador = saneador;
    }

    @Override
    public ResultadoDaIngestao executar(List<EventoDeBusca> eventos) {
        var aceitos = new ArrayList<EventoDeBusca>(eventos.size());
        var motivos = new HashMap<String, Integer>();

        for (EventoDeBusca evento : eventos) {
            String recusa = recusar(evento);
            if (recusa == null) {
                aceitos.add(evento);
            } else {
                motivos.merge(recusa, 1, Integer::sum);
            }
        }

        if (!aceitos.isEmpty()) {
            fila.publicar(aceitos);
        }

        int descartados = eventos.size() - aceitos.size();
        return new ResultadoDaIngestao(aceitos.size(), descartados, Map.copyOf(motivos));
    }

    /** {@code null} quando o evento pode seguir. */
    private String recusar(EventoDeBusca evento) {
        if (!evento.temCliente()) {
            // Sem identificador não dá para contar clientes distintos, e sem
            // isso o piso de k-anonimato não tem o que medir. Um evento que
            // não pode ser contado com segurança não entra.
            return "SEM_IDENTIFICADOR";
        }

        if (evento.tipo() == TipoDeEvento.BUSCA_DIGITADA
                || evento.tipo() == TipoDeEvento.SUGESTAO_CLICADA) {

            Saneamento veredito = saneador.avaliar(evento.termo());
            if (!veredito.aceito()) {
                return veredito.motivo();
            }
        }

        // A impressão carrega termos que saíram do nosso próprio índice, então
        // já passaram pelo portão uma vez. Reavaliar é defesa em profundidade e
        // custa quase nada: um índice envenenado por outro caminho pararia aqui.
        for (String exibido : evento.termosExibidos()) {
            if (!saneador.avaliar(exibido).aceito()) {
                return "TERMO_EXIBIDO_SUSPEITO";
            }
        }

        return null;
    }
}
