package dev.sugestao.busca.dominio;

import java.util.List;
import java.util.Optional;

/**
 * O resultado. A lista vazia é resposta legítima — prefixo curto demais, ou
 * nada sobreviveu à peneira — e nunca um erro para o cliente.
 */
public record ListaDeSugestoes(List<Sugestao> itens, boolean degradada) {

    public ListaDeSugestoes {
        itens = itens == null ? List.of() : List.copyOf(itens);
    }

    public static ListaDeSugestoes vazia() {
        return new ListaDeSugestoes(List.of(), false);
    }

    /**
     * A melhor oferta <em>desta lista</em>: a primeira linha que identifica uma
     * oferta, numa lista que já chega ordenada pelo ranking.
     *
     * <p>Três decisões estão embutidas aqui, e as três são deliberadas.
     *
     * <p><strong>Primeira: é "a melhor da lista", não "a melhor que existe".</strong>
     * Uma oferta cortada pelo teto de resultados ou por cota não entra. Anunciar
     * como melhor oferta algo que não foi entregue daria ao consumidor um
     * identificador que ele não tem como explicar — a oferta não apareceu para o
     * cliente, não vai ter impressão registrada, e o aprendizado nunca saberá o
     * que aconteceu com ela. Melhor oferta é entre as que de fato saíram.
     *
     * <p><strong>Segunda: é posicional, não um segundo ranking.</strong> Quem
     * decidiu a ordem foi o {@link Rankeador}, com jornada líder e afinidade do
     * cliente. Reordenar aqui por qualquer outro critério criaria duas respostas
     * à mesma pergunta, e a resposta exibida divergiria da resposta acionada.
     *
     * <p><strong>Terceira: pode não haver nenhuma.</strong> Prefixo que só casa
     * com artigo de ajuda, ou cliente com todas as ofertas vetadas, devolve
     * {@link Optional#empty()} — e isso não é erro nem lista degradada. A
     * ausência é informação: significa que, para esta pessoa e este prefixo, não
     * há oferta elegível. Devolver a primeira linha qualquer só para o campo não
     * vir vazio seria inventar oferta.
     */
    public Optional<Sugestao> melhorOferta() {
        return itens.stream().filter(Sugestao::ehOferta).findFirst();
    }

    /** A posição da melhor oferta na lista, ou -1 quando não há oferta. */
    public int posicaoDaMelhorOferta() {
        for (int i = 0; i < itens.size(); i++) {
            if (itens.get(i).ehOferta()) {
                return i;
            }
        }
        return -1;
    }
}
