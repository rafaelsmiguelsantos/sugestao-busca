package dev.sugestao.busca.aplicacao.casodeuso;

import dev.sugestao.busca.aplicacao.ParametrosDeSugestao;
import dev.sugestao.busca.aplicacao.Prazos;
import dev.sugestao.busca.aplicacao.porta.entrada.ConsultaDeSugestao;
import dev.sugestao.busca.aplicacao.porta.saida.CatalogoDeSugestoes;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.Candidato;
import dev.sugestao.busca.dominio.ClassificadorDeOrigem;
import dev.sugestao.busca.dominio.Cliente;
import dev.sugestao.busca.dominio.Cotas;
import dev.sugestao.busca.dominio.Misturador;
import dev.sugestao.busca.dominio.Origem;
import dev.sugestao.busca.dominio.Peneira;
import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.Segmento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O caso de uso inteiro, sem Spring, sem Solr e sem rede. É o que a separação de
 * camadas compra: cada degradação do desenho vira um teste de milissegundos.
 */
class SugerirSugestoesCasoDeUsoTest {

    private static final Cliente CLIENTE = new Cliente("c-1", Segmento.PF);

    private static final ParametrosDeSugestao PARAMETROS = new ParametrosDeSugestao(
            2, 20, Cotas.padrao(), Prazos.de(18, 8, 30));

    @Test
    @DisplayName("prefixo curto demais não chega a tocar em nenhuma dependência")
    void prefixoCurtoNaoConsultaNada() {
        var indiceTocado = new AtomicBoolean(false);
        var perfilTocado = new AtomicBoolean(false);

        var caso = montar(
                (prefixo, segmento, limite) -> {
                    indiceTocado.set(true);
                    return List.of();
                },
                id -> {
                    perfilTocado.set(true);
                    return Perfil.NEUTRO;
                });

        var lista = caso.executar(new ConsultaDeSugestao("a", CLIENTE));

        assertThat(lista.itens()).isEmpty();
        assertThat(indiceTocado).isFalse();
        assertThat(perfilTocado).isFalse();
    }

    @Test
    @DisplayName("perfil que estoura o prazo vira lista popular, marcada como degradada")
    void perfilLentoFalhaAberta() {
        var caso = montar(
                (prefixo, segmento, limite) -> List.of(popular("pagar boleto")),
                id -> {
                    dormir(200);
                    return new Perfil(List.of("pix agendado"), Set.of(), Set.of(), Set.of(), true);
                });

        var lista = caso.executar(new ConsultaDeSugestao("pa", CLIENTE));

        assertThat(lista.degradada()).isTrue();
        assertThat(lista.itens()).extracting(s -> s.termo()).containsExactly("pagar boleto");
    }

    @Test
    @DisplayName("perfil pronto não é descartado só porque o índice gastou o orçamento antes dele")
    void perfilProntoSobreviveAoPrazoJaConsumido() {
        // As duas leituras correm em paralelo, mas são colhidas em sequência.
        // O índice leva quase o prazo inteiro; quando chega a vez do perfil, o
        // relógio absoluto dele já venceu — e ele respondeu na hora.
        var caso = montar(
                (prefixo, segmento, limite) -> {
                    dormir(16);
                    return List.of(popular("pagar boleto"));
                },
                id -> new Perfil(List.of("pagar pix"), Set.of(), Set.of(), Set.of(), true));

        var lista = caso.executar(new ConsultaDeSugestao("pa", CLIENTE));

        assertThat(lista.degradada()).isFalse();
        assertThat(lista.itens()).extracting(s -> s.origem())
                .contains(Origem.HISTORICO, Origem.POPULAR);
    }

    @Test
    @DisplayName("índice fora do ar ainda devolve o histórico — a caixa de busca não fica sem resposta")
    void indiceForaDoArDevolveHistorico() {
        var caso = montar(
                (prefixo, segmento, limite) -> {
                    throw new IllegalStateException("índice fora do ar");
                },
                id -> new Perfil(List.of("pagar boleto"), Set.of(), Set.of(), Set.of(), true));

        var lista = caso.executar(new ConsultaDeSugestao("pa", CLIENTE));

        assertThat(lista.itens()).singleElement()
                .satisfies(s -> assertThat(s.origem()).isEqualTo(Origem.HISTORICO));
    }

    @Test
    @DisplayName("gatilho ativo promove o candidato a evento e o põe na primeira linha")
    void gatilhoAtivoViraEvento() {
        var comGatilho = new Candidato("antecipação de limite", "pela sua última tentativa de Pix",
                "/app/credito/antecipacao", "credito", "ANTECIPACAO", false,
                List.of("pix_recusado_72h"), 5000, 1.0, Origem.CATALOGO);

        var caso = montar(
                (prefixo, segmento, limite) -> List.of(popular("antecipar agora"), comGatilho),
                id -> new Perfil(List.of(), Set.of("pix_recusado_72h"), Set.of(), Set.of(), true));

        var lista = caso.executar(new ConsultaDeSugestao("ante", CLIENTE));

        assertThat(lista.itens().getFirst().origem()).isEqualTo(Origem.EVENTO);
        assertThat(lista.itens().getFirst().rotulo()).isEqualTo("pela sua última tentativa de Pix");
        assertThat(lista.degradada()).isFalse();
    }

    @Test
    @DisplayName("o prefixo chega normalizado ao índice, não como o cliente digitou")
    void normalizaAntesDeConsultar() {
        var recebido = new java.util.concurrent.atomic.AtomicReference<String>();

        var caso = montar(
                (prefixo, segmento, limite) -> {
                    recebido.set(prefixo.valor());
                    return List.of();
                },
                id -> Perfil.NEUTRO);

        caso.executar(new ConsultaDeSugestao("  ANTECIPAÇÃO  ", CLIENTE));

        assertThat(recebido.get()).isEqualTo("antecipacao");
    }

    // -----------------------------------------------------------------------

    private SugerirSugestoesCasoDeUso montar(CatalogoDeSugestoes catalogo, RepositorioDePerfil perfis) {
        return new SugerirSugestoesCasoDeUso(
                catalogo, perfis, new ClassificadorDeOrigem(), new Peneira(30),
                new Misturador(), PARAMETROS);
    }

    private static Candidato popular(String termo) {
        return new Candidato(termo, "", "/busca?q=" + termo, "busca", "",
                false, List.of(), 1000, 1.0, Origem.POPULAR);
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
