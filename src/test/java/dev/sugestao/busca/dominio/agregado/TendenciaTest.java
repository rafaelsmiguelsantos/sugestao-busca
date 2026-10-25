package dev.sugestao.busca.dominio.agregado;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A tendência: janela curta contra janela longa.
 *
 * <p>Este é o sinal de que menos se confia no projeto, e os testes aqui existem
 * sobretudo para fixar <strong>onde ele não vale</strong>. A fraqueza foi
 * declarada antes de a implementação começar — razão entre números pequenos é
 * ruído — e o {@code pisoDaTendencia} é a resposta a ela. Metade destes casos
 * verifica o piso, não a conta.
 */
class TendenciaTest {

    private static final PesosDeAfinidade PADRAO = PesosDeAfinidade.padrao();

    // -----------------------------------------------------------------------
    // O piso: sem evidência, neutro
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("abaixo do piso de evidência a tendência é exatamente neutra")
    void semEvidenciaEhNeutra() {
        // Dois cliques (4 pontos) é o piso. Abaixo disso, qualquer razão é
        // acidente: com três pontos no total, um clique a mais move a razão de
        // 0,33 para 0,66 e a "tendência" dobraria por causa de um toque.
        assertThat(PADRAO.tendenciaDe(2.0, 2.0)).isEqualTo(1.0);
        assertThat(PADRAO.tendenciaDe(0.0, 3.9)).isEqualTo(1.0);
        assertThat(PADRAO.tendenciaDe(3.9, 3.9)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("sem pontos na janela longa a tendência é neutra, não zero")
    void semPontosEhNeutra() {
        // 1,0 e não 0,0, e a diferença é tudo: isto é multiplicador. Zero
        // zeraria a linha de quem não tem evidência — que é a maioria das
        // pessoas na maior parte do tempo.
        assertThat(PADRAO.tendenciaDe(0.0, 0.0)).isEqualTo(1.0);
    }

    // -----------------------------------------------------------------------
    // O ponto neutro não é 0,5
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("o neutro é a razão entre as janelas: 7/30, e não 0,5")
    void oNeutroEhARazaoDasJanelas() {
        assertThat(PADRAO.tendenciaNeutra()).isCloseTo(7.0 / 30.0, within(0.0001));
    }

    @Test
    @DisplayName("atividade uniforme ao longo dos 30 dias dá tendência 1,0")
    void uniformeEhNeutra() {
        // 30 pontos espalhados por 30 dias deixam 7 na janela curta. A razão é
        // 7/30, igual ao neutro, e o resultado normalizado é 1,0 — "está do
        // jeito esperado", não "está baixo".
        assertThat(PADRAO.tendenciaDe(7.0, 30.0)).isCloseTo(1.0, within(0.01));
    }

    // -----------------------------------------------------------------------
    // Subindo e esfriando
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("tudo concentrado na última semana é tendência de alta")
    void concentradaNoPresenteSobe() {
        // 20 pontos, todos na janela curta: razão 1,0 contra neutro de 0,233.
        // Isto é a necessidade transitória que a meia-vida sozinha não distingue
        // de interesse constante.
        assertThat(PADRAO.tendenciaDe(20.0, 20.0)).isCloseTo(30.0 / 7.0, within(0.01));
        assertThat(PADRAO.tendenciaDe(20.0, 20.0)).isGreaterThan(1.0);
    }

    @Test
    @DisplayName("nada na última semana é tendência de baixa")
    void concentradaNoPassadoEsfria() {
        // 20 pontos na janela longa, zero na curta: o interesse existiu e
        // parou. Razão 0, tendência 0.
        assertThat(PADRAO.tendenciaDe(0.0, 20.0)).isZero();
    }

    @Test
    @DisplayName("metade da atividade na última semana ainda é alta")
    void metadeRecenteEhAlta() {
        // Razão 0,5 contra neutro 0,233: mais que o dobro do esperado.
        assertThat(PADRAO.tendenciaDe(10.0, 20.0)).isCloseTo(0.5 / (7.0 / 30.0), within(0.01));
    }

    // -----------------------------------------------------------------------
    // A fraqueza declarada, medida
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("com três cliques o sinal seria ruído — e o piso o silencia")
    void oRuidoQueOPisoSilencia() {
        // Este caso existe para mostrar o problema que o piso resolve, em
        // números. Três cliques dão 6 pontos. Se os três estiverem na semana, a
        // razão é 1,0; se dois estiverem, é 0,67. Sem piso, a diferença entre
        // "clicou três vezes" e "clicou duas vezes" mudaria a tendência em 50%.
        //
        // Com 6 pontos o piso de 4,0 JÁ foi vencido, então a tendência vale — e
        // isto é a escolha de calibração, não um acidente. Vale a pena ver o
        // número para saber o que se está aceitando.
        double tresNaSemana = PADRAO.tendenciaDe(6.0, 6.0);
        double doisNaSemana = PADRAO.tendenciaDe(4.0, 6.0);

        assertThat(tresNaSemana).isGreaterThan(doisNaSemana);
        assertThat(tresNaSemana / doisNaSemana).isCloseTo(1.5, within(0.01));

        // E abaixo do piso os dois são iguais: neutros.
        assertThat(PADRAO.tendenciaDe(3.0, 3.0)).isEqualTo(PADRAO.tendenciaDe(1.0, 3.0));
    }
}
