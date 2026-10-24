package dev.sugestao.busca.dominio;

import java.util.List;

/**
 * As linhas escolhidas, em ordem, e o que a dedup e as cotas deixaram de fora.
 *
 * <p>Devolve {@link Candidato} e não {@link Sugestao} porque o candidato ainda
 * carrega a relevância — e é ela que responde "por que esta linha e não aquela".
 * A conversão para a linha de tela acontece uma camada acima.
 */
public record Mistura(List<Candidato> escolhidos, List<Descarte> descartes) {

    public Mistura {
        escolhidos = List.copyOf(escolhidos);
        descartes = List.copyOf(descartes);
    }
}
