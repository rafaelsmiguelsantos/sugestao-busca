package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;
import dev.sugestao.busca.dominio.agregado.PesosDeAfinidade;
import dev.sugestao.busca.dominio.agregado.PesosDeFadiga;
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
     * <p>Várias janelas, porque os sinais medem coisas diferentes. O
     * <strong>histórico</strong> é "o que você digitou ultimamente" e é curto —
     * lista de conveniência. A <strong>afinidade</strong> é "em que você
     * demonstrou interesse" e é longa com meia-vida curta: interesse em crédito
     * imobiliário não se demonstra todo dia, e cortar em 24 h chamaria de
     * desinteresse o que é só intervalo entre acessos. A <strong>tendência</strong>
     * tem duas janelas próprias, porque ela é uma razão entre períodos. E a
     * <strong>fadiga</strong> tem a sua, mais curta, porque ignorar uma oferta em
     * março não deve pesar em outubro.
     *
     * <p>São dois objetos de peso e não cinco durações soltas: os números que
     * governam cada sinal viajam junto com a regra que os usa, e o compilador
     * impede passar a meia-vida da afinidade no lugar da janela da fadiga.
     */
    List<ComportamentoDoCliente> comportamentos(
            Duration janelaDoHistorico, int tamanhoDoHistorico,
            PesosDeAfinidade pesos, PesosDeFadiga fadiga);

    int eventosRetidos();
}
