package dev.sugestao.busca.dominio.agregado;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * A fadiga é o único sinal que rebaixa, e por isso é o que mais precisa de
 * limite testado. Cada caso aqui corresponde a uma das três defesas da classe —
 * piso de evidência, teto e janela — mais o zeramento por clique.
 */
class PesosDeFadigaTest {

    private static final PesosDeFadiga PADRAO = PesosDeFadiga.padrao();

    // -----------------------------------------------------------------------
    // Piso de evidência: oferta nova não morre antes de nascer
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("abaixo do piso de evidência não há penalidade nenhuma")
    void abaixoDoPisoNaoPenaliza() {
        // É a defesa mais importante da classe. Sem ela: oferta nova recebe
        // impressão, não tem clique, é penalizada, perde posição, e nunca chega
        // a receber o clique que a tiraria da penalidade.
        for (int impressoes = 0; impressoes <= 5; impressoes++) {
            assertThat(PADRAO.fadigaDe(impressoes, 0))
                    .as("com %d impressoes", impressoes)
                    .isZero();
        }
    }

    @Test
    @DisplayName("uma impressão acima do piso já penaliza, mas pouco")
    void acimaDoPisoComecaDevagar() {
        // 6 impressoes: uma acima do piso de 5. Andou 1 de 15 na faixa ate o
        // teto, logo 1/15 = 0,067 da penalidade configurada.
        assertThat(PADRAO.fadigaDe(6, 0)).isCloseTo(1.0 / 15.0, within(0.001));
    }

    // -----------------------------------------------------------------------
    // Clique zera — não desconta
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("um clique zera a fadiga, mesmo depois de cinquenta impressões")
    void umCliqueZera() {
        // A pergunta que a fadiga responde é "esta pessoa ignora esta linha?", e
        // um clique responde não. Descontar proporcionalmente deixaria uma linha
        // clicada uma vez em cinquenta impressões penalizada para sempre —
        // quando ela é justamente uma linha que funciona, só raramente.
        assertThat(PADRAO.fadigaDe(50, 1)).isZero();
        assertThat(PADRAO.fadigaDe(50, 0)).isEqualTo(1.0);
    }

    // -----------------------------------------------------------------------
    // Teto: rebaixa, nunca remove
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a fadiga para no teto e não cresce além dele")
    void oTetoSegura() {
        assertThat(PADRAO.fadigaDe(20, 0)).isEqualTo(1.0);
        assertThat(PADRAO.fadigaDe(200, 0)).isEqualTo(1.0);
        assertThat(PADRAO.fadigaDe(20_000, 0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("o crescimento entre piso e teto é linear")
    void crescimentoLinear() {
        // Não há motivo para ser outra coisa. A curva exponencial da afinidade
        // existe porque lá o eixo é tempo, e tempo tem meia-vida; aqui o eixo é
        // contagem de impressões, que não tem física nenhuma por trás.
        double a = PADRAO.fadigaDe(10, 0);
        double b = PADRAO.fadigaDe(15, 0);
        double c = PADRAO.fadigaDe(20, 0);

        assertThat(b - a).isCloseTo(c - b, within(0.0001));
    }

    @Test
    @DisplayName("a curva inteira, em números")
    void aCurvaInteira() {
        assertThat(PADRAO.fadigaDe(5, 0)).isZero();
        assertThat(PADRAO.fadigaDe(10, 0)).isCloseTo(5.0 / 15.0, within(0.001));
        assertThat(PADRAO.fadigaDe(15, 0)).isCloseTo(10.0 / 15.0, within(0.001));
        assertThat(PADRAO.fadigaDe(20, 0)).isCloseTo(1.0, within(0.001));
    }

    // -----------------------------------------------------------------------
    // Configuração incoerente
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("piso de evidência zero é recusado na construção")
    void pisoZeroRecusado() {
        assertThatThrownBy(() -> new PesosDeFadiga(0, 20, Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primeiro clique");
    }

    @Test
    @DisplayName("a profundidade da penalidade NÃO é decidida aqui")
    void profundidadeNaoMoraAqui() {
        // Esta classe tinha um `fadigaMaxima` de 0,4 e o ranking tinha um
        // `fadigaMinima` de 0,6. Os dois se multiplicavam, e no teto o fator saía
        // 1 − 0,4 × (1 − 0,6) = 0,84 — uma perda de 16% que não é o que nenhum
        // dos dois números dizia.
        //
        // Agora esta classe devolve a FRAÇÃO da penalidade (0 a 1), e a
        // profundidade é uma decisão só, em PesosDeRanking.fadigaMinima — onde há
        // um teste próprio provando que 0 e valores acima de 1 são recusados.
        //
        // Este teste existe para que a volta do segundo teto seja uma mudança
        // deliberada e não uma adição inocente: no limite, a fadiga vale 1,0
        // exato, e quem traduz 1,0 em desconto é o ranking.
        assertThat(PADRAO.fadigaDe(PADRAO.impressoesParaOTeto(), 0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("teto igual ao piso faria da fadiga um degrau, e é recusado")
    void degrauRecusado() {
        assertThatThrownBy(() -> new PesosDeFadiga(5, 5, Duration.ofDays(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("degrau");
    }
}
