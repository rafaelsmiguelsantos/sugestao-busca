package dev.sugestao.busca.dominio;

import java.util.List;

/** O que sobrou da peneira, e o que ela tirou. */
public record Peneirada(List<Candidato> sobreviventes, List<Descarte> descartes) {

    public Peneirada {
        sobreviventes = List.copyOf(sobreviventes);
        descartes = List.copyOf(descartes);
    }
}
