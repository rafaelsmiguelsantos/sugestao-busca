package dev.sugestao.busca.adaptador.entrada.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sugestao.busca.aplicacao.RastroDaSugestao;
import dev.sugestao.busca.aplicacao.porta.entrada.ResultadoDaSugestao;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RespostaDeSugestoes(
        String prefixo,
        boolean degradada,

        /*
         * A melhor oferta da lista, repetida no topo. Nulo quando a lista nao
         * tem oferta nenhuma, e nulo some do JSON.
         *
         * E redundante de proposito. Uma jornada que chama esta API para decidir
         * o que oferecer tem uma pergunta so — "qual oferta eu aciono?" — e sem
         * este campo ela precisaria varrer `itens`, saber que oferta e o item com
         * `ofertaId` preenchido, e confiar que a ordem do array e o ranking. Tres
         * suposicoes sobre a nossa implementacao, dentro do codigo dela, que
         * quebram em silencio no dia em que mudarmos qualquer uma. Aqui a
         * resposta e um campo, e a regra e nossa.
         */
        MelhorOferta melhorOferta,

        List<Item> itens,
        Explicacao explicacao) {

    /**
     * Uma linha da lista.
     *
     * <p>{@code ofertaId}, {@code jornada} e {@code produtoAlvo} vem vazios em
     * tres casos legitimos — termo aprendido do log, item de navegacao, e linha
     * do historico do cliente — e vazio aqui significa "nao e oferta", nunca
     * "oferta cujo id nao sabemos". Nao ha o terceiro caso: o cadastro exige o
     * identificador, e a publicacao do log escreve vazio explicitamente.
     *
     * <p>{@code relevancia} sai sempre, e nao so com {@code ?explicar=true},
     * porque e o unico numero que diz <em>quanto</em> uma linha ganhou da
     * seguinte. Uma jornada que recebe duas ofertas quase empatadas pode decidir
     * nao empurrar nenhuma; sem o numero, ela nao tem como saber que foi empate.
     * E diferente da {@code Explicacao}, que conta o <em>por que</em> e expoe o
     * que o cliente nao pode contratar.
     */
    public record Item(
            int posicao,
            String ofertaId,
            String termo,
            String rotulo,
            String destino,
            String jornada,
            String produtoAlvo,
            String origem,
            double relevancia) {
    }

    /**
     * O atalho para quem consome, nao para quem exibe.
     *
     * <p>Carrega a posicao em que a oferta saiu na lista porque e isso que liga
     * esta resposta a impressao que o app vai registrar: a jornada aciona a
     * oferta, o app registra a linha, e o aprendizado precisa dos dois lados
     * falando da mesma posicao.
     */
    public record MelhorOferta(
            String ofertaId,
            String termo,
            String jornada,
            String produtoAlvo,
            String destino,
            int posicao,
            String origem,
            double relevancia) {
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
            String jornadaLider,
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
                    sugestao.ofertaId(),
                    sugestao.termo(),
                    sugestao.rotulo(),
                    sugestao.destino(),
                    sugestao.jornada(),
                    sugestao.produtoAlvo(),
                    sugestao.origem().name(),
                    arredondar(sugestao.relevancia())));
        }

        return new RespostaDeSugestoes(
                prefixo,
                lista.degradada(),
                melhorOferta(lista),
                List.copyOf(itens),
                explicar ? explicar(resultado.rastro()) : null);
    }

    /**
     * Quem decide qual e a melhor oferta e o dominio, em
     * {@link dev.sugestao.busca.dominio.ListaDeSugestoes#melhorOferta()}. Aqui
     * so se traduz. Se esta borda escolhesse, haveria duas definicoes de melhor
     * oferta no projeto, e a do HTTP seria a que o cliente ve.
     */
    private static MelhorOferta melhorOferta(ListaDeSugestoes lista) {
        return lista.melhorOferta()
                .map(oferta -> new MelhorOferta(
                        oferta.ofertaId(),
                        oferta.termo(),
                        oferta.jornada(),
                        oferta.produtoAlvo(),
                        oferta.destino(),
                        lista.posicaoDaMelhorOferta(),
                        oferta.origem().name(),
                        arredondar(oferta.relevancia())))
                .orElse(null);
    }

    /**
     * Seis casas. O score e um numero de ordenacao, nao de contabilidade: a
     * precisao dupla inteira enche o JSON de 0.8999999999999999 e sugere ao
     * consumidor uma exatidao que o ranking nao tem.
     */
    private static double arredondar(double valor) {
        return Math.round(valor * 1_000_000.0) / 1_000_000.0;
    }

    private static Explicacao explicar(RastroDaSugestao rastro) {
        return new Explicacao(
                rastro.prefixoNormalizado(),
                rastro.perfil().name(),
                rastro.jornadaLider(),
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
