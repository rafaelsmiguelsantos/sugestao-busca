package dev.sugestao.busca.dominio.agregado;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O que a janela recente diz sobre uma pessoa: o que ela buscou, o que
 * aconteceu com ela, e em que jornadas e produtos ela de fato mexeu.
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
        Map<String, Double> afinidadePorJornada,

        /**
         * O mesmo, um nível abaixo: por produto.
         *
         * <p>É o que distingue consignado de empréstimo dentro de
         * {@code credito}. Vem do campo {@code produto} do evento de clique, que
         * já existia no contrato e não era usado para nada.
         */
        Map<String, Double> afinidadePorProduto,

        /**
         * A razão entre a janela curta e a longa, normalizada em 1,0 = neutro.
         *
         * <p>Ausente significa evidência insuficiente, não desinteresse — e por
         * isso o consumidor trata ausência como 1,0 e não como 0.
         */
        Map<String, Double> tendenciaPorJornada,

        /**
         * Quanto cada termo exibido e ignorado deve perder, entre 0 e o teto.
         *
         * <p>É o único mapa aqui que <em>rebaixa</em>. Ver {@link PesosDeFadiga}
         * para o que isso contraria e por quê.
         */
        Map<String, Double> fadigaPorTermo) {

    public ComportamentoDoCliente {
        historico = historico == null ? List.of() : List.copyOf(historico);
        gatilhos = gatilhos == null ? Set.of() : Set.copyOf(gatilhos);
        afinidadePorJornada = afinidadePorJornada == null ? Map.of() : Map.copyOf(afinidadePorJornada);
        afinidadePorProduto = afinidadePorProduto == null ? Map.of() : Map.copyOf(afinidadePorProduto);
        tendenciaPorJornada = tendenciaPorJornada == null ? Map.of() : Map.copyOf(tendenciaPorJornada);
        fadigaPorTermo = fadigaPorTermo == null ? Map.of() : Map.copyOf(fadigaPorTermo);
    }

    public ComportamentoDoCliente(String clienteId, List<String> historico, Set<String> gatilhos) {
        this(clienteId, historico, gatilhos, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public ComportamentoDoCliente(String clienteId, List<String> historico, Set<String> gatilhos,
                                  Map<String, Double> afinidadePorJornada) {
        this(clienteId, historico, gatilhos, afinidadePorJornada, Map.of(), Map.of(), Map.of());
    }
}
