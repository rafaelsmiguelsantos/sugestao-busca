package dev.sugestao.busca.adaptador.saida.agregado;

import dev.sugestao.busca.dominio.Segmento;
import dev.sugestao.busca.dominio.evento.EventoDeBusca;
import dev.sugestao.busca.dominio.evento.TipoDeEvento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgregadosEmMemoriaTest {

    private static final Duration JANELA = Duration.ofHours(24);
    private static final String CLIENTE = "pf-teste";

    private final AgregadosEmMemoria agregados = new AgregadosEmMemoria(10_000);

    private static EventoDeBusca clique(String jornada) {
        return new EventoDeBusca(TipoDeEvento.SUGESTAO_CLICADA, CLIENTE, Segmento.PF,
                "um termo", List.of(), 0, "", jornada, false, Instant.now());
    }

    private static EventoDeBusca busca(String termo) {
        return new EventoDeBusca(TipoDeEvento.BUSCA_DIGITADA, CLIENTE, Segmento.PF,
                termo, List.of(), -1, "", "", false, Instant.now());
    }

    @Test
    @DisplayName("a jornada da afinidade é normalizada: 'Crédito' conta como 'credito'")
    void normalizaAJornada() {
        // Regressão de falha silenciosa. A jornada vinha do corpo do evento sem
        // passar por normalização e era comparada por igualdade exata com o
        // `jornada_s` do índice, que é "credito". Um app que mandasse "Crédito"
        // tinha a afinidade contada, guardada, e sem nenhum efeito no ranking —
        // o tipo de coisa que se descobre meses depois perguntando por que o
        // ranking por comportamento não mexe em nada.
        agregados.acumular(List.of(clique("Crédito"), clique("CREDITO "), clique("credito")));

        var comportamentos = agregados.comportamentos(JANELA, 10);

        assertThat(comportamentos).singleElement()
                .satisfies(c -> {
                    assertThat(c.afinidadePorJornada()).containsOnlyKeys("credito");
                    assertThat(c.afinidadePorJornada().get("credito")).isEqualTo(1.0);
                });
    }

    @Test
    @DisplayName("o sentinela 'busca' NÃO acumula afinidade")
    void sentinelaNaoAcumula() {
        // Regressão, e a mais sutil das três.
        //
        // O Rankeador barra `busca` da eleição de jornada líder de propósito:
        // ela é o balde dos termos do log e do histórico, e deixá-la concorrer
        // faria dela a líder quase sempre, só por ser a mais numerosa. Mas a
        // afinidade era aplicada por jornada do candidato, inclusive para esse
        // balde — então três cliques em linha de histórico davam afinidade 1,0
        // com `busca`, e TODA linha vinda do log passava a ser promovida.
        //
        // O sinal mais fraco do conjunto virava o mais forte, pela porta de trás.
        agregados.acumular(List.of(clique("busca"), clique("busca"), clique("credito")));

        var comportamentos = agregados.comportamentos(JANELA, 10);

        assertThat(comportamentos).singleElement()
                .satisfies(c -> assertThat(c.afinidadePorJornada()).containsOnlyKeys("credito"));
    }

    @Test
    @DisplayName("jornada vazia ou nula não acumula, e não explode")
    void jornadaAusenteNaoAcumula() {
        agregados.acumular(List.of(clique(""), clique(null), clique("   ")));

        // Nenhuma afinidade, e nenhum cliente devolvido: não houve nem busca
        // nem afinidade para reportar.
        assertThat(agregados.comportamentos(JANELA, 10)).isEmpty();
    }

    @Test
    @DisplayName("cliente que só clicou aparece com histórico vazio — e é por isso que a escrita não sobrescreve")
    void soCliqueVemComHistoricoVazio() {
        // Documenta o comportamento que motivou a correção em PerfisEmMemoria.
        // Não é um defeito daqui: a agregação monta cada dimensão do que viu, e
        // vazio significa "esta janela não teve nada a dizer sobre isto".
        agregados.acumular(List.of(clique("credito")));

        assertThat(agregados.comportamentos(JANELA, 10)).singleElement()
                .satisfies(c -> {
                    assertThat(c.historico()).isEmpty();
                    assertThat(c.afinidadePorJornada()).isNotEmpty();
                });
    }

    @Test
    @DisplayName("histórico guarda a busca mais recente primeiro, deduplicada")
    void historicoMaisRecentePrimeiro() {
        agregados.acumular(List.of(busca("pix"), busca("boleto"), busca("pix")));

        assertThat(agregados.comportamentos(JANELA, 10)).singleElement()
                .satisfies(c -> assertThat(c.historico()).containsExactly("pix", "boleto"));
    }

    @Test
    @DisplayName("a afinidade é relativa à própria pessoa: a jornada mais forte vale 1")
    void afinidadeRelativaAoProprioCliente() {
        // Normalizar contra a população transformaria esta medida num segundo
        // índice de popularidade, que já existe no ctr.
        agregados.acumular(List.of(
                clique("credito"), clique("credito"), clique("credito"),
                clique("investimento")));

        assertThat(agregados.comportamentos(JANELA, 10)).singleElement()
                .satisfies(c -> {
                    assertThat(c.afinidadePorJornada().get("credito")).isEqualTo(1.0);
                    assertThat(c.afinidadePorJornada().get("investimento")).isLessThan(0.5);
                });
    }
}
