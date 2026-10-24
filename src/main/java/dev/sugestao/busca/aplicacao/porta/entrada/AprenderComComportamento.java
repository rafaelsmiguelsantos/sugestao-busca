package dev.sugestao.busca.aplicacao.porta.entrada;

/**
 * Consome a fila, agrega as janelas e publica o que passar pelo piso.
 *
 * <p>É porta de entrada como qualquer outra — só que quem a aciona não é um
 * cliente, é um relógio. Modelá-la assim mantém a regra testável sem agendador
 * e deixa óbvio que ela roda <em>fora</em> do caminho de 30 ms: ninguém está
 * esperando o resultado desta chamada.
 */
public interface AprenderComComportamento {

    ResultadoDoAprendizado executar();
}
