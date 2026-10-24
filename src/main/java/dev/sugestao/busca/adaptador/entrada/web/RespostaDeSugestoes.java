package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.dominio.ListaDeSugestoes;

import java.util.ArrayList;
import java.util.List;

/**
 * O que sai na resposta HTTP. É um tipo da borda, não do domínio: o formato do
 * JSON é contrato com o app e muda por motivo de app, não por motivo de regra.
 *
 * <p>A posição vem numerada do servidor. Quem registra a impressão é o app, mas
 * quem sabe em que linha cada sugestão foi colocada é quem montou a lista — e
 * sem posição registrada a camada 4 aprende que a primeira linha é a melhor,
 * quando ela é apenas onde a linha estava.
 */
public record RespostaDeSugestoes(String prefixo, boolean degradada, List<Item> itens) {

    public record Item(int posicao, String termo, String rotulo, String destino, String origem) {
    }

    public static RespostaDeSugestoes de(String prefixo, ListaDeSugestoes lista) {
        var itens = new ArrayList<Item>(lista.itens().size());
        for (int i = 0; i < lista.itens().size(); i++) {
            var sugestao = lista.itens().get(i);
            itens.add(new Item(
                    i,
                    sugestao.termo(),
                    sugestao.rotulo(),
                    sugestao.destino(),
                    sugestao.origem().name()));
        }
        return new RespostaDeSugestoes(prefixo, lista.degradada(), List.copyOf(itens));
    }
}
