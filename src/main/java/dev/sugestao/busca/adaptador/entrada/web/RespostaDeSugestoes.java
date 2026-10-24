package dev.sugestao.busca.adaptador.entrada.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sugestao.busca.aplicacao.RastroDaSugestao;
import dev.sugestao.busca.aplicacao.porta.entrada.ResultadoDaSugestao;

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
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RespostaDeSugestoes(
        String prefixo,
        boolean degradada,
        List<Item> itens,
        Explicacao explicacao) {

    public record Item(int posicao, String termo, String rotulo, String destino, String origem) {
    }

    /**
     * O rastro da decisão, exposto só quando pedido e só onde está habilitado.
     *
     * <p>Não é contrato com o app: é instrumento de quem precisa responder por
     * que uma oferta não apareceu. Em produção isto fica desligado — a lista de
     * descartes diz quais produtos o cliente não pode contratar, e isso é
     * informação sobre a pessoa, não sobre a busca.
     */
    public record Explicacao(
            String prefixoNormalizado,
            String perfil,
            long msTotal,
            long msAteIndice,
            long msAtePerfil,
            int candidatosDoIndice,
            int candidatosDoHistorico,
            int promovidosAEvento,
            List<ItemDescartado> descartes,
            List<LinhaPontuada> linhas) {

        public record ItemDescartado(String termo, String origem, String motivo) {
        }

        public record LinhaPontuada(int posicao, String termo, String origem, double relevancia) {
        }
    }

    public static RespostaDeSugestoes de(String prefixo, ResultadoDaSugestao resultado, boolean explicar) {
        var lista = resultado.lista();

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

        return new RespostaDeSugestoes(
                prefixo,
                lista.degradada(),
                List.copyOf(itens),
                explicar ? explicar(resultado.rastro()) : null);
    }

    private static Explicacao explicar(RastroDaSugestao rastro) {
        return new Explicacao(
                rastro.prefixoNormalizado(),
                rastro.perfil().name(),
                rastro.msTotal(),
                rastro.msAteIndice(),
                rastro.msAtePerfil(),
                rastro.candidatosDoIndice(),
                rastro.candidatosDoHistorico(),
                rastro.promovidosAEvento(),
                rastro.descartes().stream()
                        .map(d -> new Explicacao.ItemDescartado(
                                d.termo(), d.origem().name(), d.motivo().name()))
                        .toList(),
                rastro.linhas().stream()
                        .map(l -> new Explicacao.LinhaPontuada(
                                l.posicao(), l.termo(), l.origem().name(), l.relevancia()))
                        .toList());
    }
}
