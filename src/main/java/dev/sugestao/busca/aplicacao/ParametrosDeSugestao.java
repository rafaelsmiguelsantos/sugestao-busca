package dev.sugestao.busca.aplicacao;

import dev.sugestao.busca.dominio.Cotas;
import dev.sugestao.busca.dominio.PesosDeRanking;

/**
 * Tudo o que o desenho chama de "parâmetro", reunido num valor só. Fica aqui, e
 * não espalhado em constantes, porque cada um destes números é uma decisão que
 * alguém vai querer discutir — e discutir número escondido em código é discutir
 * no escuro.
 */
public record ParametrosDeSugestao(
        int prefixoMinimo,

        /*
         * O teto do prefixo. Nao e regra de relevancia, e controle de custo: o
         * analisador de EdgeNGram roda sobre o texto da consulta a cada tecla, e
         * um prefixo de dez mil caracteres e trabalho real no Solr por uma
         * requisicao que ninguem digitou. Passar do teto devolve lista vazia,
         * nunca erro.
         */
        int prefixoMaximo,

        int candidatosDoIndice,
        Cotas cotas,
        PesosDeRanking pesos,
        Prazos prazos) {

    /**
     * Oitenta caracteres. Ninguém busca com oitenta; quem manda oitenta não está
     * buscando. É o padrão dos construtores de conveniência — em produção o
     * valor vem de {@code sugestao.prefixo-maximo}.
     */
    private static final int PREFIXO_MAXIMO_PADRAO = 80;

    /** Sem pesos declarados, usa os padrão. Conveniência para teste. */
    public ParametrosDeSugestao(int prefixoMinimo, int candidatosDoIndice, Cotas cotas, Prazos prazos) {
        this(prefixoMinimo, PREFIXO_MAXIMO_PADRAO, candidatosDoIndice, cotas,
                PesosDeRanking.padrao(), prazos);
    }

    /** Com pesos, sem teto de prefixo declarado. Conveniência para teste. */
    public ParametrosDeSugestao(
            int prefixoMinimo, int candidatosDoIndice, Cotas cotas, PesosDeRanking pesos, Prazos prazos) {
        this(prefixoMinimo, PREFIXO_MAXIMO_PADRAO, candidatosDoIndice, cotas, pesos, prazos);
    }

    public ParametrosDeSugestao {
        if (prefixoMinimo < 1) {
            throw new IllegalArgumentException("prefixo mínimo precisa ser ao menos 1");
        }
        if (prefixoMaximo < prefixoMinimo) {
            throw new IllegalArgumentException(
                    "teto do prefixo menor que o piso recusaria toda busca em silêncio");
        }
        if (candidatosDoIndice < cotas.limite()) {
            throw new IllegalArgumentException(
                    "pedir ao índice menos candidatos do que cabe na lista deixa a cota sem o que escolher");
        }
    }

    /**
     * O tamanho de lista que vale para esta requisição.
     *
     * <p>Zero ou ausente usa o configurado. Acima do configurado é <em>limitado
     * em silêncio</em>, não recusado: o teto existe para proteger o serviço, e
     * recusar com erro ensinaria ao chamador exatamente onde ele está — além de
     * transformar um pedido grande demais em falha, quando devolver oito é uma
     * resposta perfeitamente útil. Negativo cai no padrão pelo mesmo motivo.
     */
    public int limiteEfetivo(int solicitado) {
        if (solicitado <= 0) {
            return cotas.limite();
        }
        return Math.min(solicitado, cotas.limite());
    }
}
