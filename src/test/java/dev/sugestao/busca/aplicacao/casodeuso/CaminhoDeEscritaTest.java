package dev.sugestao.busca.aplicacao.casodeuso;

import dev.sugestao.busca.adaptador.saida.agregado.AgregadosEmMemoria;
import dev.sugestao.busca.adaptador.saida.fila.FilaEmMemoria;
import dev.sugestao.busca.aplicacao.ParametrosDeAprendizado;
import dev.sugestao.busca.aplicacao.porta.saida.EscritorDePerfil;
import dev.sugestao.busca.aplicacao.porta.saida.PublicadorDeVocabulario;
import dev.sugestao.busca.dominio.Segmento;
import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;
import dev.sugestao.busca.dominio.agregado.TermoAgregado;
import dev.sugestao.busca.dominio.evento.EventoDeBusca;
import dev.sugestao.busca.dominio.evento.Saneador;
import dev.sugestao.busca.dominio.evento.TipoDeEvento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O caminho de escrita de ponta a ponta, sem Solr e sem Spring: ingestão,
 * agregação em janela, piso de k-anonimato e publicação.
 */
class CaminhoDeEscritaTest {

    private static final int PISO = 30;

    private final FilaEmMemoria fila = new FilaEmMemoria(10_000);
    private final AgregadosEmMemoria agregados = new AgregadosEmMemoria(100_000);
    private final PublicadorFalso publicador = new PublicadorFalso();
    private final EscritorFalso perfis = new EscritorFalso();

    private final RegistrarEventosCasoDeUso ingestao =
            new RegistrarEventosCasoDeUso(fila, new Saneador());

    private final AprenderComComportamentoCasoDeUso aprendizado =
            new AprenderComComportamentoCasoDeUso(fila, agregados, publicador, perfis,
                    ParametrosDeAprendizado.padrao(PISO));

    @Test
    @DisplayName("termo com PII não chega nem na fila")
    void piiParaNaIngestao() {
        var resultado = ingestao.executar(List.of(
                busca("c-1", "pagar boleto"),
                busca("c-2", "adiantar para cpf 111.444.777-35")));

        assertThat(resultado.aceitos()).isEqualTo(1);
        assertThat(resultado.descartados()).isEqualTo(1);
        assertThat(resultado.motivos()).containsEntry("CPF", 1);
        assertThat(fila.pendentes()).isEqualTo(1);
    }

    @Test
    @DisplayName("o resultado da ingestão não carrega o termo barrado")
    void naoVazaOTermoBarrado() {
        var resultado = ingestao.executar(List.of(
                busca("c-1", "pagar boleto do joao 123.456.789-09")));

        // Devolver o termo recriaria no corpo da resposta — e no log de quem o
        // imprimisse — exatamente o vazamento que o portão acabou de impedir.
        assertThat(resultado.motivos().keySet()).allSatisfy(motivo ->
                assertThat(motivo).doesNotContain("joao").doesNotContain("123"));
    }

    @Test
    @DisplayName("evento sem identificador não entra: sem ele não há como contar clientes distintos")
    void exigeIdentificador() {
        var resultado = ingestao.executar(List.of(busca(null, "pagar boleto")));

        assertThat(resultado.aceitos()).isZero();
        assertThat(resultado.motivos()).containsEntry("SEM_IDENTIFICADOR", 1);
    }

    @Test
    @DisplayName("termo digitado por gente de menos é retido pelo piso; o mesmo termo passa quando chega a gente suficiente")
    void oPisoSeguraEDepoisLibera() {
        // Vinte e nove pessoas distintas. Um a menos que o piso.
        ingestao.executar(eventosDeClientesDistintos("antecipar salario", 29));
        var primeira = aprendizado.executar();

        assertThat(primeira.termosPublicados()).isZero();
        assertThat(primeira.retidosPeloPiso()).isEqualTo(1);
        assertThat(publicador.publicados).isEmpty();

        // A trigésima pessoa digita o mesmo termo.
        ingestao.executar(eventosDeClientesDistintos("antecipar salario", 30));
        var segunda = aprendizado.executar();

        assertThat(segunda.termosPublicados()).isEqualTo(1);
        assertThat(publicador.publicados).singleElement().satisfies(t -> {
            assertThat(t.termo()).isEqualTo("antecipar salario");
            assertThat(t.clientesDistintos()).isEqualTo(30);
        });
    }

    @Test
    @DisplayName("a mesma pessoa repetindo não vira consenso")
    void repeticaoNaoSubstituiGente() {
        var muitas = new ArrayList<EventoDeBusca>();
        for (int i = 0; i < 100; i++) {
            muitas.add(busca("c-insistente", "antecipar salario"));
        }
        ingestao.executar(muitas);

        var resultado = aprendizado.executar();

        // Cem buscas, uma pessoa. Contar sessão em vez de cliente transformaria
        // insistência em consenso, que é o buraco que o piso existe para fechar.
        assertThat(resultado.termosPublicados()).isZero();
        assertThat(resultado.retidosPeloPiso()).isEqualTo(1);
    }

    @Test
    @DisplayName("o CTR tem impressão no denominador, não busca")
    void ctrUsaImpressaoComoDenominador() {
        ingestao.executar(eventosDeClientesDistintos("pagar boleto", 30));

        var exibicoes = new ArrayList<EventoDeBusca>();
        for (int i = 0; i < 10; i++) {
            exibicoes.add(evento(TipoDeEvento.SUGESTAO_EXIBIDA, "c-" + i, "", List.of("pagar boleto")));
        }
        exibicoes.add(evento(TipoDeEvento.SUGESTAO_CLICADA, "c-1", "pagar boleto", List.of()));
        exibicoes.add(evento(TipoDeEvento.SUGESTAO_CLICADA, "c-2", "pagar boleto", List.of()));
        exibicoes.add(evento(TipoDeEvento.ADESAO, "c-1", "pagar boleto", List.of()));
        ingestao.executar(exibicoes);

        aprendizado.executar();

        assertThat(publicador.publicados).singleElement().satisfies(t -> {
            assertThat(t.exibicoes()).isEqualTo(10);
            assertThat(t.cliques()).isEqualTo(2);
            assertThat(t.ctr()).isEqualTo(0.2);
            // Adesão sobre clique, não sobre impressão: é o que separa oferta
            // que converte de isca que só atrai o toque.
            assertThat(t.taxaAdesao()).isEqualTo(0.5);
        });
    }

    @Test
    @DisplayName("o histórico do cliente volta para o perfil dele")
    void historicoVoltaParaODono() {
        ingestao.executar(List.of(
                busca("c-1", "pagar boleto"),
                busca("c-1", "limite do pix"),
                busca("c-2", "cdb")));

        aprendizado.executar();

        assertThat(perfis.recebidos).hasSize(2);
        assertThat(perfis.recebidos)
                .filteredOn(c -> c.clienteId().equals("c-1"))
                .singleElement()
                .satisfies(c -> assertThat(c.historico())
                        .containsExactlyInAnyOrder("pagar boleto", "limite do pix"));
    }

    // -----------------------------------------------------------------------

    private static List<EventoDeBusca> eventosDeClientesDistintos(String termo, int quantos) {
        var eventos = new ArrayList<EventoDeBusca>(quantos);
        for (int i = 0; i < quantos; i++) {
            eventos.add(busca("c-" + i, termo));
        }
        return eventos;
    }

    private static EventoDeBusca busca(String cliente, String termo) {
        return evento(TipoDeEvento.BUSCA_DIGITADA, cliente, termo, List.of());
    }

    private static EventoDeBusca evento(
            TipoDeEvento tipo, String cliente, String termo, List<String> exibidos) {
        return new EventoDeBusca(tipo, cliente, Segmento.PF, termo, exibidos,
                -1, "", "", false, Instant.now());
    }

    private static final class PublicadorFalso implements PublicadorDeVocabulario {
        private final List<TermoAgregado> publicados = new ArrayList<>();

        @Override
        public int publicar(List<TermoAgregado> termos) {
            publicados.clear();
            publicados.addAll(termos);
            return termos.size();
        }
    }

    private static final class EscritorFalso implements EscritorDePerfil {
        private final List<ComportamentoDoCliente> recebidos = new ArrayList<>();

        @Override
        public void atualizar(List<ComportamentoDoCliente> comportamentos) {
            recebidos.clear();
            recebidos.addAll(comportamentos);
        }
    }
}
