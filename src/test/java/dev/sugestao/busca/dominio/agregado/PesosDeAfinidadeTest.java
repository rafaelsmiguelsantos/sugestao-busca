package dev.sugestao.busca.dominio.agregado;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PesosDeAfinidadeTest {

    private static final PesosDeAfinidade PADRAO = PesosDeAfinidade.padrao();
    private static final Instant AGORA = Instant.parse("2026-10-25T12:00:00Z");

    @Test
    @DisplayName("evento de agora vale o peso cheio")
    void agoraValeCheio() {
        assertThat(PADRAO.pesoComIdade(2.0, AGORA.minusSeconds(1), AGORA))
                .isCloseTo(2.0, within(0.001));
    }

    @Test
    @DisplayName("uma meia-vida corta o peso ao meio; duas, a um quarto")
    void meiaVidaCortaAoMeio() {
        var seteDiasAtras = AGORA.minus(Duration.ofDays(7));
        var quatorzeDiasAtras = AGORA.minus(Duration.ofDays(14));

        assertThat(PADRAO.pesoComIdade(2.0, seteDiasAtras, AGORA)).isCloseTo(1.0, within(0.01));
        assertThat(PADRAO.pesoComIdade(2.0, quatorzeDiasAtras, AGORA)).isCloseTo(0.5, within(0.01));
    }

    @Test
    @DisplayName("o decaimento é contínuo, não em degraus")
    void decaimentoContinuo() {
        // Importa porque degrau faz a afinidade dar saltos visíveis na lista:
        // a pessoa veria a ordem mudar de uma vez num dia arbitrário, em vez de
        // ir cedendo. Meia-vida fracionária tem de funcionar.
        double tresDias = PADRAO.pesoComIdade(2.0, AGORA.minus(Duration.ofDays(3)), AGORA);
        double quatroDias = PADRAO.pesoComIdade(2.0, AGORA.minus(Duration.ofDays(4)), AGORA);

        assertThat(tresDias).isGreaterThan(quatroDias);
        assertThat(tresDias).isLessThan(2.0).isGreaterThan(1.0);
    }

    @Test
    @DisplayName("evento com data no futuro vale o peso cheio, não mais")
    void futuroNaoRendeMais() {
        // Relógio de aparelho erra, e um evento adiantado não deve render mais
        // que um evento honesto. A ingestão já defende contra isso; aqui é cinto
        // e suspensório.
        var daquiUmaHora = AGORA.plus(Duration.ofHours(1));
        var daquiUmAno = AGORA.plus(Duration.ofDays(365));

        assertThat(PADRAO.pesoComIdade(2.0, daquiUmaHora, AGORA)).isEqualTo(2.0);
        assertThat(PADRAO.pesoComIdade(2.0, daquiUmAno, AGORA)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("o piso de confiança é o que impede um clique de valer o máximo")
    void pisoDeConfianca() {
        // 2 pontos (um clique) sobre piso 6 = 0,33. Era aqui que a versão
        // anterior dava 1,0, porque dividia pelo máximo da própria pessoa — e
        // com um clique só, o máximo dela É aquele clique.
        assertThat(PADRAO.afinidadeDe(2.0)).isCloseTo(0.333, within(0.001));
        assertThat(PADRAO.afinidadeDe(5.0)).isCloseTo(0.833, within(0.001));
        assertThat(PADRAO.afinidadeDe(6.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("afinidade é limitada em 1, quantos cliques venham")
    void limitadaEmUm() {
        // O limite é o que preserva a propriedade que a normalização pelo
        // próprio máximo protegia: quem clicou três vezes e quem clicou
        // trezentas chegam ao mesmo lugar, e nenhum é comparado com a população.
        assertThat(PADRAO.afinidadeDe(60.0)).isEqualTo(1.0);
        assertThat(PADRAO.afinidadeDe(6000.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ponto zero ou negativo é afinidade zero")
    void zeroEhZero() {
        assertThat(PADRAO.afinidadeDe(0.0)).isZero();
        assertThat(PADRAO.afinidadeDe(-1.0)).isZero();
    }

    @Test
    @DisplayName("configuração incoerente é recusada na construção")
    void configuracaoIncoerente() {
        // Piso zero devolveria afinidade máxima para o primeiro clique, que é
        // exatamente o defeito que esta classe existe para corrigir.
        assertThatThrownBy(() -> pesos(Duration.ofDays(90), Duration.ofDays(7), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("primeiro clique");

        // Janela menor que a meia-vida corta o evento antes de ele ter decaído
        // de forma apreciável: o esquecimento volta a ser um corte seco, que é o
        // que esta classe substitui.
        assertThatThrownBy(() -> pesos(Duration.ofDays(1), Duration.ofDays(7), 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("corte seco");

        assertThatThrownBy(() -> pesos(Duration.ZERO, Duration.ofDays(7), 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pesos(Duration.ofDays(90), Duration.ZERO, 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("janela curta >= longa faz a tendência ser sempre 1, e é recusada")
    void tendenciaDegenerada() {
        // Com curta >= longa a razão é sempre 1: a tendência viraria uma
        // constante com nome de sinal, que é pior que não existir porque parece
        // estar funcionando.
        assertThatThrownBy(() -> new PesosDeAfinidade(
                Duration.ofDays(90), Duration.ofDays(7), 6, 4,
                Duration.ofDays(30), Duration.ofDays(30), 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sempre 1");

        // A janela longa da tendência não pode passar da janela de acumulação:
        // lá não há evento guardado, e o denominador viria truncado — inflando a
        // tendência de todo mundo.
        assertThatThrownBy(() -> new PesosDeAfinidade(
                Duration.ofDays(30), Duration.ofDays(7), 6, 4,
                Duration.ofDays(7), Duration.ofDays(60), 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("janela de acumulacao");
    }

    /** Só os três primeiros números variam nestes casos; o resto fica no padrão. */
    private static PesosDeAfinidade pesos(Duration janela, Duration meiaVida, double piso) {
        return new PesosDeAfinidade(janela, meiaVida, piso, 4.0,
                Duration.ofDays(7), Duration.ofDays(30), 4.0);
    }

    @Test
    @DisplayName("a curva inteira, em números: de 1,00 a 0,05 em um mês")
    void aCurvaInteira() {
        // Três cliques — seis pontos de hoje — conforme envelhecem.
        // É a tabela que eu quereria ver numa discussão de produto sobre
        // "quanto tempo o sistema deve lembrar".
        record Caso(long dias, double esperado) { }

        for (var caso : new Caso[] {
                new Caso(0, 1.00),
                new Caso(7, 0.50),
                new Caso(14, 0.25),
                new Caso(21, 0.125),
                new Caso(30, 0.051)
        }) {
            var quando = AGORA.minus(Duration.ofDays(caso.dias()));
            double pontos = 3 * PADRAO.pesoComIdade(2.0, quando, AGORA);

            assertThat(PADRAO.afinidadeDe(pontos))
                    .as("%d dias atras", caso.dias())
                    .isCloseTo(caso.esperado(), within(0.01));
        }
    }
}
