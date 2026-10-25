package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;
import dev.sugestao.busca.dominio.agregado.PesosDeAfinidade;
import dev.sugestao.busca.dominio.agregado.TermoAgregado;
import dev.sugestao.busca.dominio.evento.EventoDeBusca;

import java.time.Duration;
import java.util.List;

/**
 * O lugar da agregação em janelas do desenho — 24 h para o comportamento
 * individual, 30 d para o vocabulário.
 *
 * <p>É a única parte do sistema que vê evento de cliente identificado e termo
 * agregado ao mesmo tempo, e é por isso que ela é também o lugar onde o
 * identificador morre: o que sai daqui para o índice é termo e número, e o que
 * sai para o perfil volta para o próprio dono.
 */
public interface RepositorioDeAgregados {

    void acumular(List<EventoDeBusca> eventos);

    /** Vocabulário somado sobre a janela, ainda sem passar pelo piso. */
    List<TermoAgregado> termos(Duration janela);

    /**
     * Comportamento por cliente.
     *
     * <p>Duas janelas, porque histórico e afinidade medem coisas diferentes. O
     * histórico é "o que você digitou ultimamente" e é curto — lista de
     * conveniência. A afinidade é "em que você demonstrou interesse" e é longa
     * com meia-vida curta: interesse em crédito imobiliário não se demonstra
     * todo dia, e cortar em 24 h chamaria de desinteresse o que é só intervalo
     * entre acessos.
     */
    List<ComportamentoDoCliente> comportamentos(
            Duration janelaDoHistorico, int tamanhoDoHistorico, PesosDeAfinidade pesos);

    int eventosRetidos();
}
