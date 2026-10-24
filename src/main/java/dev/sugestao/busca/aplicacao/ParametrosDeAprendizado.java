package dev.sugestao.busca.aplicacao;

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
        int kAnonimatoMinimo) {

    public ParametrosDeAprendizado {
        if (kAnonimatoMinimo < 1) {
            throw new IllegalArgumentException(
                    "o piso de k-anonimato precisa ser ao menos 1; zero publica o dado de uma pessoa só");
        }
    }

    public static ParametrosDeAprendizado padrao(int kAnonimatoMinimo) {
        return new ParametrosDeAprendizado(
                Duration.ofDays(30), Duration.ofHours(24), 20, 5000, kAnonimatoMinimo);
    }
}
