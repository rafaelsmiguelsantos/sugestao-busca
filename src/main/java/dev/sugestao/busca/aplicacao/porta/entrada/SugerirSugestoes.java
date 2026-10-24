package dev.sugestao.busca.aplicacao.porta.entrada;

import dev.sugestao.busca.dominio.ListaDeSugestoes;

/**
 * A porta de entrada: tudo o que o mundo de fora pode pedir a esta aplicação.
 *
 * <p>O controller HTTP depende desta interface, não da implementação. Trocar a
 * entrada por gRPC, por uma fila ou por um teste não toca em nada abaixo daqui.
 */
public interface SugerirSugestoes {

    ListaDeSugestoes executar(ConsultaDeSugestao consulta);
}
