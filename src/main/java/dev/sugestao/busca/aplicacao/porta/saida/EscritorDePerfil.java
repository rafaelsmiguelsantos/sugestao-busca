package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;

import java.util.List;

/**
 * Escreve o perfil — o DynamoDB do desenho.
 *
 * <p>Separado de {@link RepositorioDePerfil} por direção, não por tecnologia. O
 * caminho de leitura tem 30 ms e nunca escreve; este aqui roda fora da
 * requisição e nunca é esperado por ninguém. Duas interfaces tornam isso
 * verificável no compilador: o caso de uso de sugestão não tem como escrever,
 * porque não recebe esta porta.
 */
public interface EscritorDePerfil {

    void atualizar(List<ComportamentoDoCliente> comportamentos);
}
