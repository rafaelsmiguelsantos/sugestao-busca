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
        var lista = misturador.misturar(List.of(
                Candidatos.de("Antecipação de Limite", Origem.CATALOGO, 1.0),
                Candidatos.de("antecipacao de limite", Origem.POPULAR, 0.9)),
                cotas, false);

        assertThat(lista.itens()).hasSize(1);
    }

    @Test
    @DisplayName("na colisão vence a origem mais forte, mas o destino do perdedor é aproveitado")
    void colisaoPreservaODestinoReal() {
        var doHistorico = new Candidato("antecipar limite", "", "", "busca", "",
                false, List.of(), 0, 0.9, Origem.HISTORICO);
        var doCatalogo = new Candidato("Antecipar limite", "pela sua última tentativa de Pix",
                "/app/credito/antecipacao", "credito", "ANTECIPACAO",
                false, List.of(), 1000, 1.0, Origem.CATALOGO);

        var lista = misturador.misturar(List.of(doHistorico, doCatalogo), cotas, false);

        assertThat(lista.itens()).singleElement().satisfies(item -> {
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

        var lista = misturador.misturar(candidatos, cotas, false);

        assertThat(lista.itens()).filteredOn(s -> s.origem() == Origem.HISTORICO).hasSize(2);
        assertThat(lista.itens()).hasSize(cotas.limite());
    }

    @Test
    @DisplayName("o evento abre a lista mesmo pontuando menos que todo o resto")
    void eventoOcupaAPrimeiraLinha() {
        var candidatos = new ArrayList<Candidato>();
        candidatos.add(Candidatos.de("antecipar limite", Origem.EVENTO, 0.01));
        for (int i = 0; i < 5; i++) {
            candidatos.add(Candidatos.doIndice("popular " + i, 1.0, 1000));
        }

        var lista = misturador.misturar(candidatos, cotas, false);

        assertThat(lista.itens().getFirst().origem()).isEqualTo(Origem.EVENTO);
        assertThat(lista.itens()).filteredOn(s -> s.origem() == Origem.EVENTO).hasSize(1);
    }

    @Test
    @DisplayName("a lista nunca passa do limite de posições")
    void cortaNoLimite() {
        var candidatos = new ArrayList<Candidato>();
        for (int i = 0; i < 40; i++) {
            candidatos.add(Candidatos.doIndice("termo " + i, i, 1000));
        }

        assertThat(misturador.misturar(candidatos, cotas, false).itens()).hasSize(8);
    }

    @Test
    @DisplayName("score do índice e score do histórico são postos na mesma escala antes de competir")
    void normalizaScoresDeOrigensDiferentes() {
        // O Solr devolve score em escala própria; aqui, uma ordem de grandeza
        // acima do histórico. Sem normalizar, o histórico nunca entraria.
        var candidatos = List.of(
                Candidatos.doIndice("popular alto", 87.4, 1000),
                Candidatos.de("meu historico", Origem.HISTORICO, 0.95));

        var lista = misturador.misturar(candidatos, Cotas.padrao(), false);

        assertThat(lista.itens().getFirst().origem()).isEqualTo(Origem.POPULAR);
        assertThat(lista.itens()).hasSize(2);
    }
}
