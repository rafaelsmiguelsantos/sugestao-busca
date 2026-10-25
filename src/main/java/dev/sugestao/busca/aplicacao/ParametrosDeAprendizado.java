package dev.sugestao.busca.aplicacao;

import dev.sugestao.busca.dominio.agregado.PesosDeAfinidade;

import java.time.Duration;

/**
 * Os números do caminho de escrita, reunidos num valor só.
 *
 * <p>As duas janelas são as do desenho e têm motivos diferentes. A do
 * vocabulário é longa porque ela precisa juntar gente suficiente para o piso de
 * k-anonimato fazer sentido — janela curta demais e quase nada passa, o que
 * parece conservador mas na prática empurra alguém a baixar o piso. A do
 * comportamento é curta porque gatilho que não expira deixa de ser oportunidade
 * e vira perseguição: um Pix recusado há três semanas não explica nada sobre o
 * que a pessoa está fazendo agora.
 */
public record ParametrosDeAprendizado(
        Duration janelaDoVocabulario,
        Duration janelaDoComportamento,
        int tamanhoDoHistorico,
        int loteDeConsumo,
        int kAnonimatoMinimo,

        /*
         * A terceira janela, e ela tem motivo proprio. A do comportamento acima
         * governa o HISTORICO, que e lista de conveniencia e e curta. Esta
         * governa a AFINIDADE, que mede interesse demonstrado e por isso e longa
         * com meia-vida curta. Ver PesosDeAfinidade.
         */
        PesosDeAfinidade afinidade) {

    public ParametrosDeAprendizado {
        if (kAnonimatoMinimo < 1) {
            throw new IllegalArgumentException(
                    "o piso de k-anonimato precisa ser ao menos 1; zero publica o dado de uma pessoa só");
        }
        afinidade = afinidade == null ? PesosDeAfinidade.padrao() : afinidade;
    }

    /** Sem pesos de afinidade declarados, usa os padrão. Conveniência para teste. */
    public ParametrosDeAprendizado(
            Duration janelaDoVocabulario,
            Duration janelaDoComportamento,
            int tamanhoDoHistorico,
            int loteDeConsumo,
            int kAnonimatoMinimo) {

        this(janelaDoVocabulario, janelaDoComportamento, tamanhoDoHistorico,
                loteDeConsumo, kAnonimatoMinimo, PesosDeAfinidade.padrao());
    }

    public static ParametrosDeAprendizado padrao(int kAnonimatoMinimo) {
        return new ParametrosDeAprendizado(
                Duration.ofDays(30), Duration.ofHours(24), 20, 5000, kAnonimatoMinimo,
                PesosDeAfinidade.padrao());
    }
}
