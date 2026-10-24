package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.dominio.Perfil;

/**
 * O registro de perfil — no desenho, um DynamoDB alimentado fora da requisição.
 * Leitura de chave única, 1 a 5 ms, sem agregação nenhuma no caminho crítico.
 *
 * <p>A implementação pode falhar ou demorar; quem decide o que fazer com isso é
 * o caso de uso, que tem o prazo e o caminho alternativo. Esta porta não captura
 * exceção nem devolve {@code Optional} vazio por conta própria.
 */
public interface RepositorioDePerfil {

    Perfil porCliente(String clienteId);
}
