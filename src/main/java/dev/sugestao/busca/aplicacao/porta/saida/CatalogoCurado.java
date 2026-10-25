package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.dominio.agregado.ItemCurado;

import java.util.List;

/**
 * O que alguém curou à mão: produto, ação e artigo de ajuda, cada um com sua
 * jornada declarada.
 *
 * <p>É lido uma vez por rodada de aprendizado, para servir de régua à
 * classificação dos termos vindos do log. Fica no caminho de escrita, onde não
 * há prazo — na leitura de 30 ms uma consulta a mais não caberia.
 */
public interface CatalogoCurado {

    List<ItemCurado> itens();
}
