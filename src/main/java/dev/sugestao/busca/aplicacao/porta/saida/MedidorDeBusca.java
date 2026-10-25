package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.aplicacao.RastroDaSugestao;

/**
 * Para onde vai a medição da qualidade da busca.
 *
 * <p>Existe porque nenhuma das melhorias de relevância é verificável sem ela.
 * Tolerância a erro de digitação, sinal negativo, afinidade por produto — os três
 * são afirmações sobre quantas buscas passam a encontrar algo, e sem taxa de
 * zero-resultado medida antes e depois, "melhorou" é opinião. Esta porta foi
 * construída primeiro de propósito.
 *
 * <p><strong>Porta, e não chamada direta ao Micrometer.</strong> O caso de uso
 * não conhece framework nenhum, e essa propriedade é justamente o que permite
 * testá-lo inteiro sem subir contexto. Um {@code MeterRegistry} injetado no caso
 * de uso custaria essa propriedade para economizar um arquivo.
 *
 * <p><strong>O que esta porta recebe e o que o adaptador pode fazer com
 * isso.</strong> O {@link RastroDaSugestao} carrega o prefixo digitado e o
 * identificador do cliente, porque ele também serve ao log de depuração. Um
 * adaptador de métrica <em>não pode</em> transformar nenhum dos dois em rótulo:
 * prefixo é texto digitado por uma pessoa — é exatamente o que o piso de
 * k-anonimato protege — e identificador de cliente é a própria identidade. Além
 * da privacidade, há o custo: rótulo por prefixo é cardinalidade ilimitada, e
 * cardinalidade ilimitada derruba o coletor antes de contar qualquer coisa útil.
 *
 * <p>A implementação em produção é
 * {@link dev.sugestao.busca.adaptador.saida.metrica.MedidorMicrometer}. O
 * {@link #NULO} é para teste e para quando a medição está desligada: medir não
 * pode ser condição para responder.
 */
public interface MedidorDeBusca {

    /** Uma requisição de sugestão terminou. */
    void busca(RastroDaSugestao rastro);

    /**
     * O app relatou um clique na posição informada.
     *
     * <p>É a distribuição desta posição que diz se o ranking está acertando: com
     * o ranking bom, a massa concentra em 0 e 1. Se a massa está em 3 e 4, as
     * duas primeiras linhas estão ocupando lugar que não merecem.
     */
    void cliqueEm(int posicao);

    /**
     * O app relatou uma lista exibida, com quantas linhas e se era degradada.
     *
     * <p>É o denominador de tudo: sem impressão, clique é número absoluto e não
     * taxa.
     */
    void impressao(int linhasExibidas, boolean degradada);

    /**
     * Não mede nada e nunca falha.
     *
     * <p>Medição é observação, não parte da resposta — se o coletor cair, a
     * caixa de busca continua respondendo.
     */
    MedidorDeBusca NULO = new MedidorDeBusca() {

        @Override
        public void busca(RastroDaSugestao rastro) {
            // de propósito, nada
        }

        @Override
        public void cliqueEm(int posicao) {
            // de propósito, nada
        }

        @Override
        public void impressao(int linhasExibidas, boolean degradada) {
            // de propósito, nada
        }
    };
}
