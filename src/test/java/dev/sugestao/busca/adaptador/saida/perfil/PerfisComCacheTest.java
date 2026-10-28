package dev.sugestao.busca.adaptador.saida.perfil;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.SituacaoDaConta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O cache do perfil por sessão de digitação.
 *
 * <p>Nenhum teste aqui dorme. O relógio é um parâmetro do construtor, e isso é o
 * que separa "o TTL funciona" de "o TTL funcionou nesta máquina nesta tarde" —
 * um {@code Thread.sleep} num teste de expiração passa na máquina de quem
 * escreveu e falha na pipeline carregada, e aí alguém aumenta o sleep em vez de
 * olhar o motivo.
 */
class PerfisComCacheTest {

    private static final Duration TTL = Duration.ofSeconds(5);

    @Test
    @DisplayName("dez teclas, uma leitura: é o motivo de o cache existir")
    void segundaLeituraNaoVaiAoAdaptador() {
        var leituras = new AtomicInteger();
        var cache = montar(id -> {
            leituras.incrementAndGet();
            return perfilDe("pagar boleto");
        }, new RelogioDeTeste());

        for (int i = 0; i < 10; i++) {
            assertThat(cache.porCliente("c-1").historico()).containsExactly("pagar boleto");
        }

        assertThat(leituras).hasValue(1);
        assertThat(cache.estatisticas().acertos()).isEqualTo(9);
        assertThat(cache.estatisticas().erros()).isEqualTo(1);
        assertThat(cache.estatisticas().taxaDeAcerto()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("cada cliente tem a própria entrada")
    void chaveiaPorCliente() {
        var leituras = new AtomicInteger();
        var cache = montar(id -> {
            leituras.incrementAndGet();
            return perfilDe(id);
        }, new RelogioDeTeste());

        assertThat(cache.porCliente("c-1").historico()).containsExactly("c-1");
        assertThat(cache.porCliente("c-2").historico()).containsExactly("c-2");
        assertThat(cache.porCliente("c-1").historico()).containsExactly("c-1");

        assertThat(leituras).hasValue(2);
    }

    @Test
    @DisplayName("vencido o TTL, a leitura volta ao adaptador — é assim que a agregação chega")
    void expiraPorEscrita() {
        var relogio = new RelogioDeTeste();
        var leituras = new AtomicInteger();
        var cache = montar(id -> {
            leituras.incrementAndGet();
            return perfilDe("leitura " + leituras.get());
        }, relogio);

        assertThat(cache.porCliente("c-1").historico()).containsExactly("leitura 1");

        relogio.avancar(TTL.minusMillis(1));
        assertThat(cache.porCliente("c-1").historico()).containsExactly("leitura 1");

        relogio.avancar(Duration.ofMillis(2));
        assertThat(cache.porCliente("c-1").historico()).containsExactly("leitura 2");

        assertThat(leituras).hasValue(2);
    }

    @Test
    @DisplayName("expira por escrita e não por acesso: quem digita sem parar não congela o perfil")
    void naoRenovaNoAcesso() {
        // Por acesso, uma pessoa digitando continuamente renovaria a entrada
        // indefinidamente, e o limite deixaria de ser "uma sessão de digitação"
        // para ser "enquanto ela digitar".
        var relogio = new RelogioDeTeste();
        var leituras = new AtomicInteger();
        var cache = montar(id -> {
            leituras.incrementAndGet();
            return Perfil.NEUTRO;
        }, relogio);

        // Uma leitura a cada segundo, por seis segundos.
        for (int i = 0; i < 6; i++) {
            cache.porCliente("c-1");
            relogio.avancar(Duration.ofSeconds(1));
        }

        // Com expiração por acesso seria 1. Por escrita, a entrada morreu aos
        // 5 s e foi recarregada uma vez.
        assertThat(leituras).hasValue(2);
    }

    @Test
    @DisplayName("falha do adaptador não é guardada: a tecla seguinte tenta de novo")
    void falhaNaoEGuardada() {
        // Um cache que memorizasse a falha transformaria um soluço de rede em
        // segundos de lista degradada para a mesma pessoa. O caso de uso continua
        // recebendo a exceção, convertendo em Colheita.FALHOU e devolvendo lista
        // degradada — comportamento que já existia.
        var tentativas = new AtomicInteger();
        var cache = montar(id -> {
            if (tentativas.incrementAndGet() <= 2) {
                throw new IllegalStateException("registro de perfil fora do ar");
            }
            return perfilDe("voltou");
        }, new RelogioDeTeste());

        assertThatThrownBy(() -> cache.porCliente("c-1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cache.porCliente("c-1")).isInstanceOf(IllegalStateException.class);

        assertThat(cache.porCliente("c-1").historico()).containsExactly("voltou");
        assertThat(tentativas).hasValue(3);
    }

    @Test
    @DisplayName("cliente sem perfil também é guardado — é quem mais se beneficia")
    void guardaOPerfilNeutro() {
        // Cliente novo está digitando pela primeira vez, e cada tecla dele seria
        // uma consulta que volta dizendo "não existe".
        var leituras = new AtomicInteger();
        var cache = montar(id -> {
            leituras.incrementAndGet();
            return Perfil.NEUTRO;
        }, new RelogioDeTeste());

        assertThat(cache.porCliente("novo").conhecido()).isFalse();
        assertThat(cache.porCliente("novo").conhecido()).isFalse();

        assertThat(leituras).hasValue(1);
    }

    @Test
    @DisplayName("o teto despeja em vez de deixar o mapa crescer")
    void respeitaOTetoDeChaves() {
        var relogio = new RelogioDeTeste();
        var cache = new PerfisComCache(id -> Perfil.NEUTRO, TTL, 10, relogio);

        for (int i = 0; i < 500; i++) {
            cache.porCliente("c-" + i);
        }

        // O despejo do Caffeine é agendado, não feito na inserção: sem esta
        // chamada o tamanho lido aqui fica perto de 500, e isso é o contrato do
        // cache e não um defeito. Ver PerfisComCache.manutencao() — o executor
        // fica assíncrono de propósito, porque despejo pago por tecla digitada
        // seria o custo no pior lugar.
        cache.manutencao();

        assertThat(cache.estatisticas().itens()).isLessThanOrEqualTo(10);
    }

    // -----------------------------------------------------------------------

    private static PerfisComCache montar(RepositorioDePerfil delegado, Ticker relogio) {
        return new PerfisComCache(delegado, TTL, 1_000, relogio);
    }

    private static Perfil perfilDe(String termoNoHistorico) {
        return new Perfil(List.of(termoNoHistorico), Set.of(), Set.of(), Set.of(),
                SituacaoDaConta.ATIVA, true);
    }

    /** Um relógio que só anda quando o teste manda. */
    private static final class RelogioDeTeste implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void avancar(Duration quanto) {
            nanos.addAndGet(quanto.toNanos());
        }
    }
}
