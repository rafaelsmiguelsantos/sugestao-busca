package dev.sugestao.busca.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificadorDeOrigemTest {

    private final ClassificadorDeOrigem classificador = new ClassificadorDeOrigem();

    private static final Perfil COM_GATILHO =
            new Perfil(List.of(), Set.of("pix_recusado_72h"), Set.of(), Set.of(), SituacaoDaConta.ATIVA, true);

    @Test
    @DisplayName("gatilho ativo promove o candidato a evento")
    void promoveOCandidatoOportuno() {
        var classificados = classificador.classificar(
                List.of(Candidatos.comGatilho("antecipação de limite", "pix_recusado_72h")),
                COM_GATILHO, 1);

        assertThat(classificados).singleElement()
                .extracting(Candidato::origem).isEqualTo(Origem.EVENTO);
    }

    @Test
    @DisplayName("gatilho que o perfil não tem ativo não promove nada")
    void naoPromoveSemGatilhoAtivo() {
        var classificados = classificador.classificar(
                List.of(Candidatos.comGatilho("antecipação de limite", "pix_recusado_72h")),
                Perfil.NEUTRO, 1);

        assertThat(classificados).singleElement()
                .extracting(Candidato::origem).isEqualTo(Origem.CATALOGO);
    }

    @Test
    @DisplayName("além da cota, o gatilho não promove — e não tira ninguém da disputa")
    void excedenteDaCotaMantemAOrigemOriginal() {
        var forte = new Candidato("antecipação de limite", "", "/app/a", "credito", "ANTECIPACAO", Restricoes.NENHUMA, List.of("pix_recusado_72h"), 5000, 16.2, Origem.CATALOGO);
        var fraco = new Candidato("como antecipar o limite", "", "/app/b", "ajuda", "", Restricoes.NENHUMA, List.of("pix_recusado_72h"), 5000, 6.3, Origem.CATALOGO);

        var classificados = classificador.classificar(List.of(forte, fraco), COM_GATILHO, 1);

        // O mais relevante vira a linha de evento; o outro continua catálogo e
        // segue disputando por score. Nenhum dos dois some.
        assertThat(classificados).hasSize(2);
        assertThat(classificados)
                .filteredOn(c -> c.origem() == Origem.EVENTO)
                .extracting(Candidato::termo)
                .containsExactly("antecipação de limite");
        assertThat(classificados)
                .filteredOn(c -> c.origem() == Origem.CATALOGO)
                .extracting(Candidato::termo)
                .containsExactly("como antecipar o limite");
    }

    @Test
    @DisplayName("o histórico não vira evento: ele não veio do índice")
    void historicoNaoEhPromovido() {
        var doHistorico = new Candidato("antecipar limite", "", "/busca", "busca", "", Restricoes.NENHUMA, List.of("pix_recusado_72h"), 0, 1.0, Origem.HISTORICO);

        assertThat(classificador.classificar(List.of(doHistorico), COM_GATILHO, 1))
                .singleElement().extracting(Candidato::origem).isEqualTo(Origem.HISTORICO);
    }
}
