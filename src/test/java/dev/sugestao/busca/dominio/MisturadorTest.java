package dev.sugestao.busca.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MisturadorTest {

    private final Misturador misturador = new Misturador();
    private final Cotas cotas = Cotas.padrao();

    @Test
    @DisplayName("a mesma intenção em duas redações ocupa uma linha, não duas")
    void deduplicaPorTermoNormalizado() {
        var mistura = misturador.misturar(List.of(
                Candidatos.de("Antecipação de Limite", Origem.CATALOGO, 1.0),
                Candidatos.de("antecipacao de limite", Origem.POPULAR, 0.9)),
                cotas);

        assertThat(mistura.escolhidos()).hasSize(1);
        assertThat(mistura.descartes()).singleElement()
                .extracting(Descarte::motivo).isEqualTo(MotivoDeDescarte.TERMO_DUPLICADO);
    }

    @Test
    @DisplayName("na colisão vence a origem mais forte, mas o destino do perdedor é aproveitado")
    void colisaoPreservaODestinoReal() {
        var doHistorico = new Candidato("antecipar limite", "", "", "busca", "", Restricoes.NENHUMA, List.of(), 0, 0.9, Origem.HISTORICO);
        var doCatalogo = new Candidato("Antecipar limite", "pela sua última tentativa de Pix",
                "/app/credito/antecipacao", "credito", "ANTECIPACAO", Restricoes.NENHUMA, List.of(), 1000, 1.0, Origem.CATALOGO);

        var mistura = misturador.misturar(List.of(doHistorico, doCatalogo), cotas);

        assertThat(mistura.escolhidos()).singleElement().satisfies(item -> {
            assertThat(item.origem()).isEqualTo(Origem.HISTORICO);
            assertThat(item.destino()).isEqualTo("/app/credito/antecipacao");
            assertThat(item.rotulo()).isEqualTo("pela sua última tentativa de Pix");
        });
    }

    @Test
    @DisplayName("o histórico não passa de duas linhas, por mais que ele pontue")
    void respeitaACotaDoHistorico() {
        var candidatos = new ArrayList<Candidato>();
        for (int i = 0; i < 6; i++) {
            candidatos.add(Candidatos.de("historico " + i, Origem.HISTORICO, 1.0));
        }
        for (int i = 0; i < 6; i++) {
            candidatos.add(Candidatos.doIndice("popular " + i, 0.1, 1000));
        }

        var mistura = misturador.misturar(candidatos, cotas);

        assertThat(mistura.escolhidos()).filteredOn(c -> c.origem() == Origem.HISTORICO).hasSize(2);
        assertThat(mistura.escolhidos()).hasSize(cotas.limite());
        assertThat(mistura.descartes())
                .filteredOn(d -> d.motivo() == MotivoDeDescarte.COTA_DE_HISTORICO)
                .hasSize(4);
    }

    @Test
    @DisplayName("o evento abre a lista mesmo pontuando menos que todo o resto")
    void eventoOcupaAPrimeiraLinha() {
        var candidatos = new ArrayList<Candidato>();
        candidatos.add(Candidatos.de("antecipar limite", Origem.EVENTO, 0.01));
        for (int i = 0; i < 5; i++) {
            candidatos.add(Candidatos.doIndice("popular " + i, 1.0, 1000));
        }

        var mistura = misturador.misturar(candidatos, cotas);

        assertThat(mistura.escolhidos().getFirst().origem()).isEqualTo(Origem.EVENTO);
        assertThat(mistura.escolhidos()).filteredOn(c -> c.origem() == Origem.EVENTO).hasSize(1);
    }

    @Test
    @DisplayName("a lista nunca passa do limite, e o que sobra sai registrado")
    void cortaNoLimite() {
        var candidatos = new ArrayList<Candidato>();
        for (int i = 0; i < 40; i++) {
            candidatos.add(Candidatos.doIndice("termo " + i, i, 1000));
        }

        var mistura = misturador.misturar(candidatos, cotas);

        assertThat(mistura.escolhidos()).hasSize(8);
        assertThat(mistura.descartes())
                .filteredOn(d -> d.motivo() == MotivoDeDescarte.ALEM_DO_LIMITE)
                .hasSize(32);
    }

    @Test
    @DisplayName("o Misturador NÃO pontua: ele obedece a relevância que recebeu")
    void naoPontuaObedece() {
        // Este teste se chamava "score do índice e do histórico são postos na
        // mesma escala antes de competir" e não verificava nada: a normalização
        // mudou para o Rankeador, e as asserções antigas passavam com ou sem
        // ela, porque o candidato do índice vinha com score maior de qualquer
        // forma. Um teste que passa nos dois mundos não guarda nenhum.
        //
        // O que o Misturador faz é obedecer. Aqui o histórico chega com
        // relevância MAIOR — como chegaria depois de o Rankeador reescalar o
        // score do Solr para 0..1 — e ele tem de sair na frente. Se o
        // Misturador voltasse a pontuar, esta ordem inverteria.
        var candidatos = List.of(
                Candidatos.doIndice("popular", 0.40, 1000),
                Candidatos.de("meu historico", Origem.HISTORICO, 0.95));

        var mistura = misturador.misturar(candidatos, Cotas.padrao());

        assertThat(mistura.escolhidos()).extracting(Candidato::termo)
                .containsExactly("meu historico", "popular");
    }
}
