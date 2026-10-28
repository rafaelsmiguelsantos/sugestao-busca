package dev.sugestao.busca.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A etapa que resgata a lista vazia, e sobretudo os casos em que ela
 * <em>não</em> deve agir — que são a maioria dos testes aqui, porque é neles
 * que o recurso pode virar outra coisa.
 */
class RecuperadorTest {

    private static final Substituto CREDITO = new Substituto(
            "credito", "simular credito", "Veja as condicoes", "/app/credito/simular");

    private static final Substituto SEGUROS = new Substituto(
            "seguros", "conhecer seguros", "Veja as coberturas", "/app/seguros/visao-geral");

    private final Recuperador recuperador =
            new Recuperador(new Substitutos(List.of(CREDITO, SEGUROS)));

    @Test
    @DisplayName("lista vazia com descarte condicional recebe a linha da jornada")
    void recuperaPorMotivoCondicional() {
        var recuperada = recuperador.recuperar(
                List.of(),
                List.of(descarte("financiamento de veiculo", "credito",
                        MotivoDeDescarte.SEM_ELEGIBILIDADE)));

        assertThat(recuperada).isPresent().get().satisfies(candidato -> {
            assertThat(candidato.termo()).isEqualTo("simular credito");
            assertThat(candidato.jornada()).isEqualTo("credito");
            assertThat(candidato.origem()).isEqualTo(Origem.SUBSTITUTO);

            // Não responde pelo piso de k-anonimato: é decisão de produto, não
            // comportamento de ninguém. Mesma razão da oferta recém-cadastrada.
            assertThat(candidato.procedencia()).isEqualTo(Procedencia.CATALOGO);

            // Sem restrição, por construção — e é isso que dispensa passar a
            // substituta pela peneira de novo.
            assertThat(candidato.restricoes()).isEqualTo(Restricoes.NENHUMA);
            assertThat(candidato.ehOferta()).isFalse();
        });
    }

    @Test
    @DisplayName("as quatro razões condicionais produzem a MESMA linha: o motivo não vaza")
    void aLinhaEFuncaoDaJornadaNaoDoMotivo() {
        // A regra de privacidade do recurso. Se a linha dependesse do motivo,
        // quem recebe a resposta saberia a decisão da peneira — "você não tem
        // elegibilidade" dito por omissão.
        var motivos = List.of(
                MotivoDeDescarte.SEM_ELEGIBILIDADE,
                MotivoDeDescarte.CONTA_INADIMPLENTE,
                MotivoDeDescarte.CONTA_NAO_OPERANTE,
                MotivoDeDescarte.PRODUTO_JA_CONTRATADO);

        var termos = motivos.stream()
                .map(motivo -> recuperador
                        .recuperar(List.of(), List.of(descarte("x", "credito", motivo)))
                        .orElseThrow()
                        .termo())
                .distinct()
                .toList();

        assertThat(termos).containsExactly("simular credito");
    }

    @Test
    @DisplayName("piso de k-anonimato não gera substituta: a própria linha seria o vazamento")
    void pisoDeKAnonimatoNaoRecupera() {
        var recuperada = recuperador.recuperar(
                List.of(),
                List.of(descarte("antecipar limite da maria", "credito",
                        MotivoDeDescarte.ABAIXO_DO_PISO)));

        assertThat(recuperada).isEmpty();
    }

    @Test
    @DisplayName("cota, duplicação e teto de lista não geram substituta")
    void motivosDeMontagemNaoRecuperam() {
        for (var motivo : List.of(MotivoDeDescarte.TERMO_DUPLICADO,
                MotivoDeDescarte.COTA_DE_HISTORICO,
                MotivoDeDescarte.COTA_DE_EVENTO,
                MotivoDeDescarte.ALEM_DO_LIMITE)) {

            assertThat(recuperador.recuperar(List.of(), List.of(descarte("x", "credito", motivo))))
                    .describedAs("motivo %s", motivo)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("lista não vazia não recebe substituta: o recurso é resgate, não enchimento")
    void naoCompletaListaExistente() {
        var recuperada = recuperador.recuperar(
                List.of(Candidatos.de("antecipacao de limite", Origem.POPULAR, 1.0)),
                List.of(descarte("credito consignado", "credito",
                        MotivoDeDescarte.SEM_ELEGIBILIDADE)));

        assertThat(recuperada).isEmpty();
    }

    @Test
    @DisplayName("sem descarte nenhum não há o que recuperar")
    void semDescartesNaoRecupera() {
        assertThat(recuperador.recuperar(List.of(), List.of())).isEmpty();
    }

    @Test
    @DisplayName("a ordem dos descartes é o critério: ganha o primeiro, que é o de maior score")
    void aOrdemDecideAJornada() {
        // A peneira percorre os candidatos na ordem do índice, melhor score
        // primeiro. Então o primeiro descarte condicional é a coisa mais forte
        // que casou com o prefixo e teve de sair.
        var recuperada = recuperador.recuperar(
                List.of(),
                List.of(descarte("seguro de vida", "seguros", MotivoDeDescarte.PRODUTO_JA_CONTRATADO),
                        descarte("credito consignado", "credito", MotivoDeDescarte.SEM_ELEGIBILIDADE)));

        assertThat(recuperada).isPresent().get()
                .extracting(Candidato::jornada).isEqualTo("seguros");
    }

    @Test
    @DisplayName("jornada sem linha declarada não interrompe: a próxima com linha ganha")
    void buracoNaTabelaNaoDesistePorCompleto() {
        // O custo declarado desta escolha: com duas jornadas removidas, a linha
        // pode vir da segunda intenção. A alternativa seria trocar uma linha
        // útil por nenhuma sempre que a tabela tivesse um buraco — e buraco é o
        // estado normal dela enquanto o catálogo cresce.
        var recuperada = recuperador.recuperar(
                List.of(),
                List.of(descarte("aumentar limite do cartao", "cartao", MotivoDeDescarte.SEM_ELEGIBILIDADE),
                        descarte("credito consignado", "credito", MotivoDeDescarte.SEM_ELEGIBILIDADE)));

        assertThat(recuperada).isPresent().get()
                .extracting(Candidato::jornada).isEqualTo("credito");
    }

    @Test
    @DisplayName("descarte sem jornada não encontra substituta")
    void jornadaAusenteNaoEncontra() {
        // Termo do log chega com Jornada.AUSENTE, que não é jornada de produto.
        assertThat(recuperador.recuperar(List.of(),
                List.of(descarte("pagar boleto", Jornada.AUSENTE, MotivoDeDescarte.PRODUTO_JA_CONTRATADO))))
                .isEmpty();
    }

    @Test
    @DisplayName("tabela vazia devolve o comportamento anterior ao recurso")
    void tabelaVaziaNaoRecupera() {
        var desligado = new Recuperador(Substitutos.NENHUM);

        assertThat(desligado.recuperar(List.of(),
                List.of(descarte("x", "credito", MotivoDeDescarte.SEM_ELEGIBILIDADE))))
                .isEmpty();
    }

    @Test
    @DisplayName("a busca pela jornada ignora caixa e acento")
    void jornadaEComparadaNormalizada() {
        assertThat(recuperador.recuperar(List.of(),
                List.of(descarte("x", "CREDITO", MotivoDeDescarte.SEM_ELEGIBILIDADE))))
                .isPresent();
    }

    // -----------------------------------------------------------------------
    // Validação de configuração: estes erros derrubam a subida, de propósito.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("duas linhas para a mesma jornada é erro de edição, não última-vence")
    void jornadaDuplicadaRecusa() {
        var outra = new Substituto("credito", "outra coisa", "", "/app/credito/outra");

        assertThatThrownBy(() -> new Substitutos(List.of(CREDITO, outra)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credito");
    }

    @Test
    @DisplayName("destino absoluto é recusado: configuração não pode virar redirecionador aberto")
    void destinoAbsolutoRecusa() {
        assertThatThrownBy(() -> new Substituto("credito", "x", "", "https://exemplo.com"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new Substituto("credito", "x", "", "//exemplo.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("substituta sem jornada, ou na jornada sentinela, nunca seria alcançada")
    void jornadaInvalidaRecusa() {
        assertThatThrownBy(() -> new Substituto("", "x", "", "/app/x"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new Substituto(Jornada.AUSENTE, "x", "", "/app/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("substituta sem termo não tem o que exibir")
    void termoVazioRecusa() {
        assertThatThrownBy(() -> new Substituto("credito", "  ", "", "/app/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Descarte descarte(String termo, String jornada, MotivoDeDescarte motivo) {
        return new Descarte(termo, jornada, Origem.CATALOGO, motivo);
    }
}
