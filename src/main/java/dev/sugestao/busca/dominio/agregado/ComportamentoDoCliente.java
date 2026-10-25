package dev.sugestao.busca.dominio.agregado;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O que a janela recente diz sobre uma pessoa: o que ela buscou, o que
 * aconteceu com ela, e em que jornadas ela de fato mexeu.
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
        Set<String> gatilhos,

        /**
         * Quanto esta pessoa mexeu em cada jornada, entre 0 e 1.
         *
         * <p>Vem de clique e contratação, não de busca. Buscar é declarar
         * dúvida; clicar e contratar é declarar interesse — e só o segundo
         * merece inclinar a ordem do que ela vê depois.
         */
        Map<String, Double> afinidadePorJornada) {

    public ComportamentoDoCliente {
        historico = historico == null ? List.of() : List.copyOf(historico);
        gatilhos = gatilhos == null ? Set.of() : Set.copyOf(gatilhos);
        afinidadePorJornada = afinidadePorJornada == null ? Map.of() : Map.copyOf(afinidadePorJornada);
    }

    public ComportamentoDoCliente(String clienteId, List<String> historico, Set<String> gatilhos) {
        this(clienteId, historico, gatilhos, Map.of());
    }
}
