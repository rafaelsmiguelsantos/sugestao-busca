package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.dominio.oferta.Oferta;

import java.time.Instant;
import java.util.List;

/**
 * A oferta como ficou gravada.
 *
 * <p>Devolver o estado gravado, e não um eco do que foi enviado, é o que faz o
 * cadastro ser conferível: o termo volta normalizado, o identificador em
 * maiúscula, os sinônimos deduplicados. Quem integra compara o que mandou com o
 * que recebeu e descobre na hora que "Empréstimo Pessoal" virou
 * "emprestimo pessoal" — em vez de descobrir mais tarde que a busca por
 * "Empréstimo" não encontra a oferta.
 */
public record RespostaDeOferta(
        String ofertaId,
        String termo,
        List<String> sinonimos,
        String rotulo,
        String destino,
        String jornada,
        String produtoAlvo,
        boolean exigeElegibilidade,
        boolean vetaInadimplencia,
        boolean exigeContaAtiva,
        List<String> gatilhos,
        List<String> segmentos,
        List<String> relacionamentos,
        Instant vigenciaInicio,
        Instant vigenciaFim,

        /*
         * Os quatro numeros que o cadastro nao aceita, devolvidos para deixar
         * claro que eles existem, que valem zero, e que nao e por falta de
         * tentar. Eles sobem pelo caminho de aprendizado, a partir de impressao
         * e clique reais, e nao por campo no corpo do POST.
         */
        Desempenho desempenho) {

    /**
     * O desempenho medido da oferta. Zerado no cadastro, sempre.
     *
     * <p>Fica num objeto proprio e nao solto na raiz porque a fronteira importa:
     * tudo fora de {@code desempenho} foi declarado por quem cadastrou; tudo
     * dentro foi medido pelo sistema. Misturar os dois niveis faria parecer que
     * {@code ctr} e tao declaravel quanto {@code rotulo}.
     */
    public record Desempenho(double ctr, double taxaAdesao, int clientesDistintos, String fonte) {

        static Desempenho noCadastro() {
            return new Desempenho(0.0, 0.0, 0,
                    "medido pelo caminho de aprendizado; nao aceito no cadastro");
        }
    }

    public static RespostaDeOferta de(Oferta oferta) {
        return new RespostaDeOferta(
                oferta.ofertaId(),
                oferta.termo(),
                oferta.sinonimos(),
                oferta.rotulo(),
                oferta.destino(),
                oferta.jornada(),
                oferta.produtoAlvo(),
                oferta.restricoes().exigeElegibilidade(),
                oferta.restricoes().vetaInadimplencia(),
                oferta.restricoes().exigeContaAtiva(),
                oferta.gatilhos(),
                oferta.segmentos().stream().map(Enum::name).sorted().toList(),
                oferta.relacionamentos().stream().map(Enum::name).sorted().toList(),
                oferta.vigenciaInicio(),
                oferta.vigenciaFim(),
                Desempenho.noCadastro());
    }
}
