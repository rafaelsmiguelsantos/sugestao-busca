package dev.sugestao.busca.adaptador.saida.resiliencia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisjuntorTest {

    private static final Duration ESPERA = Duration.ofMillis(60);

    /** Janela 10, abre com 5 falhas, mínimo de 5 chamadas, 2 sondas. */
    private static Disjuntor novo() {
        return new Disjuntor("teste", 10, 5, 5, ESPERA, 2);
    }

    private static final RuntimeException QUEBROU = new IllegalStateException("dependencia fora");

    @Test
    @DisplayName("fechado, passa e devolve o que a chamada devolveu")
    void fechadoPassa() {
        var d = novo();
        assertThat(d.executar(() -> "lista")).isEqualTo("lista");
        assertThat(d.estado()).isEqualTo(Disjuntor.Estado.FECHADO);
    }

    @Test
    @DisplayName("abre ao atingir o limite de falhas na janela")
    void abreNoLimite() {
        var d = novo();

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> d.executar(() -> { throw QUEBROU; })).isSameAs(QUEBROU);
            assertThat(d.estado())
                    .withFailMessage("abriu antes do limite, na falha %d", i + 1)
                    .isEqualTo(Disjuntor.Estado.FECHADO);
        }

        assertThatThrownBy(() -> d.executar(() -> { throw QUEBROU; })).isSameAs(QUEBROU);
        assertThat(d.estado()).isEqualTo(Disjuntor.Estado.ABERTO);
    }

    @Test
    @DisplayName("aberto, NEM CHAMA a dependência — é o motivo de ele existir")
    void abertoNaoChama() {
        var d = novo();
        var chamadas = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> d.executar(() -> {
                chamadas.incrementAndGet();
                throw QUEBROU;
            })).isSameAs(QUEBROU);
        }
        assertThat(chamadas.get()).isEqualTo(5);

        // Com o disjuntor aberto, cem teclas digitadas não viram cem conexões
        // TCP contra um host morto. O prazo faz a falha ser rápida; é isto que
        // faz ela ser barata.
        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() -> d.executar(() -> {
                chamadas.incrementAndGet();
                return "nunca";
            })).isInstanceOf(DisjuntorAberto.class);
        }
        assertThat(chamadas.get())
                .withFailMessage("a dependencia foi chamada com o disjuntor aberto")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("passada a espera, libera sondas e fecha quando uma dá certo")
    void meioAbertoFechaComSucesso() throws Exception {
        var d = novo();
        abrir(d);

        Thread.sleep(ESPERA.toMillis() + 20);

        assertThat(d.executar(() -> "voltou")).isEqualTo("voltou");
        assertThat(d.estado()).isEqualTo(Disjuntor.Estado.FECHADO);
    }

    @Test
    @DisplayName("sonda que falha reabre na hora, sem esperar a janela encher")
    void sondaQueFalhaReabre() throws Exception {
        var d = novo();
        abrir(d);
        Thread.sleep(ESPERA.toMillis() + 20);

        // A sonda era a pergunta "já voltou?", e a resposta foi não. Esperar
        // mais quatro falhas para reabrir deixaria passar tráfego contra uma
        // dependência que acabou de dizer que está fora.
        assertThatThrownBy(() -> d.executar(() -> { throw QUEBROU; })).isSameAs(QUEBROU);
        assertThat(d.estado()).isEqualTo(Disjuntor.Estado.ABERTO);
    }

    @Test
    @DisplayName("no meio-aberto só as sondas passam; o resto é recusado")
    void meioAbertoLimitaAsSondas() throws Exception {
        var d = novo();
        abrir(d);
        Thread.sleep(ESPERA.toMillis() + 20);

        var passaram = new AtomicInteger();

        // Duas sondas configuradas. A terceira chamada em diante é recusada,
        // porque o Solr volta frio e devolver de uma vez toda a carga que
        // estava sendo recusada é a forma mais comum de derrubá-lo outra vez.
        //
        // A chamada que passa devolve sucesso, o que fecha o disjuntor — então
        // conta-se quantas chegaram ao delegado antes disso.
        for (int i = 0; i < 10; i++) {
            try {
                d.executar(() -> {
                    passaram.incrementAndGet();
                    throw QUEBROU;
                });
            } catch (RuntimeException ignorada) {
                // Esperado: ou QUEBROU, ou DisjuntorAberto.
            }
        }

        // A primeira sonda falha e reabre imediatamente; as nove seguintes são
        // recusadas sem tocar o delegado.
        assertThat(passaram.get()).isEqualTo(1);
        assertThat(d.estado()).isEqualTo(Disjuntor.Estado.ABERTO);
    }

    @Test
    @DisplayName("falha isolada não abre: a amostra mínima protege serviço recém-subido")
    void amostraMinimaProtege() {
        var d = new Disjuntor("teste", 10, 8, 5, ESPERA, 2);

        // Cinco falhas atingem o limite, mas são só cinco chamadas — abaixo do
        // mínimo de oito. É o cenário do Solr frio, cujas primeiras consultas
        // estouram o prazo de 18 ms legitimamente.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> d.executar(() -> { throw QUEBROU; })).isSameAs(QUEBROU);
        }
        assertThat(d.estado()).isEqualTo(Disjuntor.Estado.FECHADO);

        // Completando o mínimo com sucessos, as cinco falhas continuam na
        // janela e agora a amostra é suficiente.
        for (int i = 0; i < 3; i++) {
            d.executar(() -> "ok");
        }
        assertThat(d.estado()).isEqualTo(Disjuntor.Estado.ABERTO);
    }

    @Test
    @DisplayName("sucesso empurra falha fora da janela: oscilação não acumula para sempre")
    void janelaDeslizaDeVerdade() {
        var d = novo();

        // Quatro falhas, e depois dez sucessos. Os dez sucessos preenchem a
        // janela inteira e expulsam as quatro falhas. Sem o anel deslizando de
        // verdade, uma dependência que falha de vez em quando abriria o
        // disjuntor depois de horas no ar.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> d.executar(() -> { throw QUEBROU; })).isSameAs(QUEBROU);
        }
        for (int i = 0; i < 10; i++) {
            d.executar(() -> "ok");
        }

        // Agora quatro falhas novas não somam com as antigas.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> d.executar(() -> { throw QUEBROU; })).isSameAs(QUEBROU);
        }
        assertThat(d.estado()).isEqualTo(Disjuntor.Estado.FECHADO);
    }

    @Test
    @DisplayName("configuração que não protege de nada é recusada na construção")
    void configuracaoIncoerente() {
        // Minimo abaixo do limite: com minimo 2 e limite 5, as 5 falhas ja
        // implicam 5 chamadas, e o minimo nunca segura nada. Aceitar seria
        // aceitar uma configuracao que mente sobre o que faz.
        assertThatThrownBy(() -> new Disjuntor("x", 10, 2, 5, ESPERA, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nao protege de nada");

        assertThatThrownBy(() -> new Disjuntor("x", 10, 10, 11, ESPERA, 1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new Disjuntor("x", 10, 10, 5, ESPERA, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sonda");
    }

    @Test
    @DisplayName("o decorador não muda o contrato da porta")
    void decoradorNaoMudaOContrato() {
        // O que a aplicação vê quando o disjuntor abre é uma exceção da porta,
        // igual à que ela já via quando o Solr respondia com erro. É por isso
        // que nenhuma linha do caso de uso precisou mudar.
        var d = novo();
        var catalogo = new CatalogoComDisjuntor(
                (prefixo, contexto, limite) -> { throw QUEBROU; }, d);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> catalogo.candidatos(
                    dev.sugestao.busca.dominio.Prefixo.de("emprestim"),
                    new dev.sugestao.busca.dominio.ContextoDeBusca(
                            dev.sugestao.busca.dominio.Segmento.PF,
                            dev.sugestao.busca.dominio.Relacionamento.VAREJO),
                    20)).isInstanceOf(RuntimeException.class);
        }

        assertThat(catalogo.estado()).isEqualTo(Disjuntor.Estado.ABERTO);
    }

    private static void abrir(Disjuntor d) {
        for (int i = 0; i < 5; i++) {
            try {
                d.executar(() -> { throw QUEBROU; });
            } catch (RuntimeException ignorada) {
                // esperado
            }
        }
        assertThat(d.estado()).isEqualTo(Disjuntor.Estado.ABERTO);
    }
}
