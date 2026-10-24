package dev.sugestao.busca.aplicacao.porta.entrada;

import dev.sugestao.busca.aplicacao.RastroDaSugestao;
import dev.sugestao.busca.dominio.ListaDeSugestoes;

/**
 * O que o caso de uso devolve: a lista para o cliente, e o rastro de como ela
 * foi montada.
 *
 * <p>O rastro sai sempre. Quem decide se ele vira log, resposta ou descarte é o
 * adaptador — montá-lo custa alguns objetos sobre vinte candidatos, e não tê-lo
 * custa não conseguir responder por que uma oferta não apareceu.
 */
public record ResultadoDaSugestao(ListaDeSugestoes lista, RastroDaSugestao rastro) {
}
