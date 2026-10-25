package dev.sugestao.busca.adaptador.saida.agregado;

import dev.sugestao.busca.dominio.Segmento;
import dev.sugestao.busca.dominio.agregado.PesosDeAfinidade;
import dev.sugestao.busca.dominio.agregado.PesosDeFadiga;
import dev.sugestao.busca.dominio.evento.EventoDeBusca;
import dev.sugestao.busca.dominio.evento.TipoDeEvento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AgregadosEmMemoriaTest {

    private static final Duration JANELA_DO_HISTORICO = Duration.ofHours(24);
    private static final PesosDeAfinidade PESOS = PesosDeAfinidade.padrao();
    private static final PesosDeFadiga FADIGA = PesosDeFadiga.padrao();
    private static final String CLIENTE = "pf-teste";

    /**
     * RELOGIO FIXO, e isto e o conserto de uma familia de testes instaveis.
     *
     * Com relogio real, um evento criado em Instant.now() e agregado alguns
     * microssegundos depois tem idade > 0, e o decaimento a desconta: tres
     * cliques "de agora" dao 5,999999993 pontos em vez de 6,0, e a afinidade sai
     * 0,9999999996 em vez de 1,0.
     *
     * Tres assercoes deste arquivo afirmavam igualdade exata nessa borda.
     * Passavam numa maquina ociosa e falhavam sob carga - a ultima delas so
     * apareceu rodando a suite doze vezes com os doze nucleos ocupados.
     *
     * Afrouxar a tolerancia era a saida facil e tem custo escondido: tolerancia
     * de 1e-6 tambem aceita um erro de verdade de 1e-6. Com o relogio fixo, as
     * assercoes de borda voltam a ser EXATAS e passam a valer mais.
     */
    private static final Instant AGORA = Instant.parse("2026-06-15T12:00:00Z");

    private final AgregadosEmMemoria agregados =
            new AgregadosEmMemoria(10_000, Clock.fixed(AGORA, ZoneOffset.UTC));

    private static EventoDeBusca clique(String jornada, long diasAtras) {
        return evento(TipoDeEvento.SUGESTAO_CLICADA, jornada, "um termo", diasAtras);
    }

    private static EventoDeBusca clique(String jornada) {
        return clique(jornada, 0);
    }

    private static EventoDeBusca adesao(String jornada) {
        return evento(TipoDeEvento.ADESAO, jornada, "um termo", 0);
    }

    private static EventoDeBusca busca(String termo) {
        return evento(TipoDeEvento.BUSCA_DIGITADA, "", termo, 0);
    }

    private static EventoDeBusca evento(
            TipoDeEvento tipo, String jornada, String termo, long diasAtras) {

        return new EventoDeBusca(tipo, CLIENTE, Segmento.PF, termo, List.of(), 0, "",
                jornada, false, AGORA.minus(diasAtras, ChronoUnit.DAYS));
    }

    private double afinidade(String jornada) {
        var comportamentos = agregados.comportamentos(JANELA_DO_HISTORICO, 10, PESOS, FADIGA);
        if (comportamentos.isEmpty()) {
            return 0.0;
        }
        return comportamentos.getFirst().afinidadePorJornada().getOrDefault(jornada, 0.0);
    }

    // -----------------------------------------------------------------------
    // Piso de confiança: pouca evidência vale pouco
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("UM clique não dá afinidade máxima — dá 0,33")
    void umCliqueNaoEhMaximo() {
        // Regressão. A afinidade era dividida pela jornada mais forte DA PRÓPRIA
        // PESSOA, e com um clique só a mais forte é aquele clique: 2,0 / 2,0 =
        // 1,0. Um clique acidental punha a pessoa no teto de promoção da
        // jornada, e combinado com a ausência de decaimento isso virava
        // preferência permanente.
        //
        // Agora o divisor é constante (piso de confiança = 6). Um clique vale 2
        // pontos, logo 0,33 — que no ranking é 1,17x, ou seja, ruído. Que é o
        // que um clique é.
        agregados.acumular(List.of(clique("credito")));

        assertThat(afinidade("credito")).isCloseTo(2.0 / 6.0, within(0.001));
    }

    @Test
    @DisplayName("três cliques chegam ao máximo; trezentos não passam dele")
    void tresCliquesChegamAoTeto() {
        agregados.acumular(List.of(clique("credito"), clique("credito"), clique("credito")));

        // isEqualTo EXATO, e isso e possivel por causa do relogio fixo.
        //
        // Com relogio real, tres cliques "de agora" tinham idade de
        // microssegundos e davam 5,999999993 pontos em vez de 6,0: a afinidade
        // saia 0,9999999988 e este teste precisava de tolerancia. Com o evento e
        // a agregacao no mesmo instante, a conta e exata — e o 1,0 passa a ser
        // uma afirmacao sobre o teto, nao sobre o decimo decimal.
        assertThat(afinidade("credito")).isEqualTo(1.0);

        // O limite em 1 é o que preserva a propriedade que a normalização pelo
        // próprio máximo protegia: quem clicou três vezes e quem clicou
        // trezentas chegam ao mesmo lugar, e nenhum dos dois é comparado com a
        // população — o que já é trabalho do ctr.
        for (int i = 0; i < 300; i++) {
            agregados.acumular(List.of(clique("credito")));
        }
        assertThat(afinidade("credito")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("adesão sozinha quase basta: 0,83 contra 0,33 de um clique")
    void adesaoValeMaisQueClique() {
        // Contratação vale 5 e clique vale 2 porque ela é o único destes sinais
        // que o negócio reconhece como resultado.
        agregados.acumular(List.of(adesao("credito")));

        assertThat(afinidade("credito")).isCloseTo(5.0 / 6.0, within(0.001));
    }

    @Test
    @DisplayName("busca digitada não gera afinidade nenhuma")
    void buscaNaoGeraAfinidade() {
        // Quem procura entender um produto não é quem o quer. A busca entra no
        // histórico; só clique e adesão entram na afinidade.
        agregados.acumular(List.of(busca("emprestimo pessoal"), busca("consignado")));

        assertThat(afinidade("credito")).isZero();
        assertThat(agregados.comportamentos(JANELA_DO_HISTORICO, 10, PESOS, FADIGA))
                .singleElement()
                .satisfies(c -> assertThat(c.afinidadePorJornada()).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Decaimento por idade do evento
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("o clique vale metade depois de uma meia-vida")
    void decaiPelaMeiaVida() {
        // Meia-vida de 7 dias: três cliques de 7 dias atrás valem 3 pontos em
        // vez de 6, logo afinidade 0,5.
        agregados.acumular(List.of(
                clique("credito", 7), clique("credito", 7), clique("credito", 7)));

        assertThat(afinidade("credito")).isCloseTo(0.5, within(0.01));
    }

    @Test
    @DisplayName("depois de um mês o interesse está praticamente frio")
    void esfriaEmUmMes() {
        // 0,5^(30/7) = 0,051. Três cliques de um mês atrás valem 0,31 ponto,
        // logo afinidade 0,05 — no ranking, 1,03x. Esfriou sem nunca ter sido
        // apagado de uma vez.
        agregados.acumular(List.of(
                clique("credito", 30), clique("credito", 30), clique("credito", 30)));

        assertThat(afinidade("credito")).isLessThan(0.06).isGreaterThan(0.04);
    }

    @Test
    @DisplayName("o recente pesa mais que o antigo, e a ordem aparece no número")
    void recenteVenceAntigo() {
        agregados.acumular(List.of(
                clique("credito", 21),
                clique("investimento", 0)));

        assertThat(afinidade("investimento")).isGreaterThan(afinidade("credito"));
    }

    @Test
    @DisplayName("rodar duas vezes seguidas dá a mesma resposta")
    void cadenciaNaoMudaOResultado() {
        // A razão de o decaimento ser por IDADE DO EVENTO e não por rodada.
        //
        // Decaimento por rodada é o que parece natural e é uma armadilha: o
        // agendador roda a cada 15 s, então um fator por rodada esvaziaria a
        // afinidade em minutos — e trocar o intervalo do agendador mudaria a
        // velocidade do esquecimento, que é decisão de produto virando efeito
        // colateral de configuração de infraestrutura.
        agregados.acumular(List.of(clique("credito"), clique("credito"), clique("credito")));

        double primeira = afinidade("credito");
        double segunda = afinidade("credito");
        double terceira = afinidade("credito");

        // IGUALDADE EXATA, e aqui ela e o conteudo do teste.
        //
        // Com relogio real esta afirmacao era falsa de direito: o decaimento e
        // por idade, a idade cresce entre as tres chamadas, e o valor caia um
        // fiapo a cada uma. O teste precisava de tolerancia de 1e-6, e passava
        // so porque as tres chamadas caiam no mesmo milissegundo — flaky, e
        // afirmando menos do que queria.
        //
        // Com relogio fixo, "rodar a agregacao uma vez ou tres vezes da a mesma
        // resposta" volta a ser exatamente o que esta escrito. Com decaimento
        // por rodada — a armadilha que esta classe evita — a terceira chamada
        // devolveria uma fracao da primeira, e a igualdade exata falharia alto.
        assertThat(segunda).isEqualTo(primeira);
        assertThat(terceira).isEqualTo(primeira);
    }

    @Test
    @DisplayName("evento fora da janela da afinidade não conta")
    void foraDaJanelaNaoConta() {
        // 90 dias de janela. O de 100 dias atrás some — e a essa altura o
        // decaimento já o tinha reduzido a 0,5^(100/7), que é 10 elevado a -5.
        // A janela é a economia de não somar o que não muda nada.
        agregados.acumular(List.of(clique("credito", 100)));

        assertThat(afinidade("credito")).isZero();
    }

    // -----------------------------------------------------------------------
    // Chave da jornada
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a jornada da afinidade é normalizada: 'Crédito' conta como 'credito'")
    void normalizaAJornada() {
        // Regressão de falha silenciosa. A jornada vinha do corpo do evento sem
        // passar por normalização e era comparada por igualdade exata com o
        // `jornada_s` do índice, que é "credito". Um app que mandasse "Crédito"
        // tinha a afinidade contada, guardada, e sem nenhum efeito no ranking.
        agregados.acumular(List.of(clique("Crédito"), clique("CREDITO "), clique("credito")));

        assertThat(agregados.comportamentos(JANELA_DO_HISTORICO, 10, PESOS, FADIGA))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.afinidadePorJornada()).containsOnlyKeys("credito");
                    assertThat(c.afinidadePorJornada().get("credito")).isEqualTo(1.0);
                });
    }

    @Test
    @DisplayName("o sentinela 'busca' NÃO acumula afinidade")
    void sentinelaNaoAcumula() {
        // A mais sutil das regressões. O Rankeador barra `busca` da eleição de
        // jornada líder de propósito: ela é o balde dos termos do log e do
        // histórico, e deixá-la concorrer faria dela a líder quase sempre, só
        // por ser a mais numerosa. Mas a afinidade era aplicada por jornada do
        // candidato, inclusive para esse balde — então três cliques em linha de
        // histórico davam afinidade máxima com `busca`, e TODA linha vinda do
        // log passava a ser promovida.
        //
        // O sinal mais fraco do conjunto virava o mais forte, pela porta de trás.
        agregados.acumular(List.of(clique("busca"), clique("busca"), clique("credito")));

        assertThat(agregados.comportamentos(JANELA_DO_HISTORICO, 10, PESOS, FADIGA))
                .singleElement()
                .satisfies(c -> assertThat(c.afinidadePorJornada()).containsOnlyKeys("credito"));
    }

    @Test
    @DisplayName("o teto da fadiga corta o perfil, e a rodada conta quem bateu nele")
    void tetoDaFadigaCortaEContabiliza() {
        // O perfil é lido A CADA TECLA, e a fadiga é o único sinal dele indexado
        // por termo — histórico tem teto de 20, afinidade de jornada tem meia
        // dúzia de chaves. Sem teto aqui, quem usa o app todo dia acumula
        // indefinidamente, e num volume de 100 mil requisições por minuto cada
        // quilobyte a mais no perfil é tráfego multiplicado por isso.
        var fadigaComTeto = new PesosDeFadiga(5, 20, Duration.ofDays(30), 3);

        // Dez termos distintos, cada um exibido o bastante para cansar, nenhum
        // clicado. Os de índice maior acumulam mais impressões, então cansam
        // mais — é assim que sabemos QUAIS deveriam sobreviver.
        var eventos = new ArrayList<EventoDeBusca>();
        for (int t = 0; t < 10; t++) {
            for (int i = 0; i <= 6 + t; i++) {
                eventos.add(new EventoDeBusca(
                        TipoDeEvento.SUGESTAO_EXIBIDA, CLIENTE, Segmento.PF, "",
                        List.of("termo " + (char) ('a' + t)), 0, "", "", false, AGORA));
            }
        }
        agregados.acumular(eventos);

        var c = agregados.comportamentos(JANELA_DO_HISTORICO, 10, PESOS, fadigaComTeto)
                .getFirst();

        assertThat(c.fadigaPorTermo())
                .as("o teto limita o tamanho do mapa")
                .hasSize(3);

        // Os três mais cansados são os três com mais impressões: h, i, j.
        assertThat(c.fadigaPorTermo())
                .as("sobrevivem os MAIS cansados, nao os primeiros encontrados")
                .containsOnlyKeys("termo h", "termo i", "termo j");

        // E o corte é CONTADO. Cortar em silêncio seria o pior dos dois mundos:
        // o perfil para de crescer e ninguém sabe se o número está folgado ou
        // apertado.
        assertThat(agregados.perfisNoTetoDeFadiga())
                .as("a rodada diz quantos perfis bateram no teto")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("sem bater no teto, o contador fica em zero")
    void semTetoContadorZerado() {
        agregados.acumular(List.of(clique("credito")));
        agregados.comportamentos(JANELA_DO_HISTORICO, 10, PESOS, FADIGA);

        assertThat(agregados.perfisNoTetoDeFadiga()).isZero();
    }

    @Test
    @DisplayName("jornada vazia ou nula não acumula, e não explode")
    void jornadaAusenteNaoAcumula() {
        agregados.acumular(List.of(clique(""), clique(null), clique("   ")));

        assertThat(agregados.comportamentos(JANELA_DO_HISTORICO, 10, PESOS, FADIGA)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Histórico, que tem janela própria
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("cliente que só clicou aparece com histórico vazio — e é por isso que a escrita não sobrescreve")
    void soCliqueVemComHistoricoVazio() {
        // Documenta o comportamento que motivou a proteção em PerfisEmMemoria.
        // Não é defeito daqui: a agregação monta cada dimensão do que viu.
        agregados.acumular(List.of(clique("credito")));

        assertThat(agregados.comportamentos(JANELA_DO_HISTORICO, 10, PESOS, FADIGA)).singleElement()
                .satisfies(c -> {
                    assertThat(c.historico()).isEmpty();
                    assertThat(c.afinidadePorJornada()).isNotEmpty();
                });
    }

    @Test
    @DisplayName("termo vazio nunca vira vocabulário")
    void termoVazioNaoViraVocabulario() {
        // O clique vale pela jornada, e o termo dele pode ser vazio — é o caso
        // dos eventos semeados da demonstração. Sem o corte, isso criava uma
        // entrada de vocabulário de chave "" que nenhuma busca alcança e que o
        // portão de publicação nem examina, porque ele ignora chave com menos de
        // dois caracteres.
        // Clique SEM termo: é como EventosDaDemonstracao semeia, porque ali o
        // que importa é a jornada.
        agregados.acumular(List.of(
                evento(TipoDeEvento.SUGESTAO_CLICADA, "credito", "", 0),
                evento(TipoDeEvento.SUGESTAO_CLICADA, "credito", "   ", 0)));

        assertThat(agregados.termos(Duration.ofDays(30)))
                .as("clique de termo vazio nao deve gerar termo")
                .isEmpty();

        // E a afinidade dele continua contando: recusar o evento por não ter
        // termo jogaria fora o sinal de afinidade junto.
        assertThat(afinidade("credito")).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("histórico guarda a busca mais recente primeiro, deduplicada")
    void historicoMaisRecentePrimeiro() {
        agregados.acumular(List.of(busca("pix"), busca("boleto"), busca("pix")));

        assertThat(agregados.comportamentos(JANELA_DO_HISTORICO, 10, PESOS, FADIGA)).singleElement()
                .satisfies(c -> assertThat(c.historico()).containsExactly("pix", "boleto"));
    }

    @Test
    @DisplayName("as duas janelas são independentes: 3 dias atrás sai do histórico e fica na afinidade")
    void janelasIndependentes() {
        // O histórico é "o que você digitou ultimamente" e é curto. A afinidade
        // é "em que você demonstrou interesse" e é longa com meia-vida curta:
        // interesse em crédito imobiliário não se demonstra todo dia, e cortar
        // em 24 h chamaria de desinteresse o que é só intervalo entre acessos.
        agregados.acumular(List.of(
                evento(TipoDeEvento.BUSCA_DIGITADA, "", "busca antiga", 3),
                clique("credito", 3)));

        var comportamentos = agregados.comportamentos(JANELA_DO_HISTORICO, 10, PESOS, FADIGA);

        assertThat(comportamentos).singleElement().satisfies(c -> {
            assertThat(c.historico()).as("fora da janela de 24 h do historico").isEmpty();
            assertThat(c.afinidadePorJornada())
                    .as("dentro da janela de 90 dias da afinidade").isNotEmpty();
        });
    }
}
