package dev.sugestao.busca.dominio.agregado;

import java.util.List;
import java.util.Set;

/**
 * O que a janela recente diz sobre uma pessoa: o que ela buscou e o que
 * aconteceu com ela.
 *
 * <p>Isto vira {@link dev.sugestao.busca.dominio.Perfil} e nunca vira documento
 * do índice. É a separação que sustenta o desenho inteiro: o comportamento
 * individual volta <em>para o dono</em> pela leitura de perfil, e o comportamento
 * agregado vira vocabulário para todos só depois do piso de k-anonimato. Uma
 * coisa nunca vira a outra.
 *
 * <p>O histórico chega da busca mais recente para a mais antiga.
 */
public record ComportamentoDoCliente(
        String clienteId,
        List<String> historico,
        Set<String> gatilhos) {

    public ComportamentoDoCliente {
        historico = historico == null ? List.of() : List.copyOf(historico);
        gatilhos = gatilhos == null ? Set.of() : Set.copyOf(gatilhos);
    }
}
