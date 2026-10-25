package dev.sugestao.busca.aplicacao.casodeuso;

import dev.sugestao.busca.aplicacao.porta.entrada.Autorizacao;
import dev.sugestao.busca.aplicacao.porta.entrada.ComandoDeCadastroDeOferta;
import dev.sugestao.busca.aplicacao.porta.entrada.JornadaNaoAutorizada;
import dev.sugestao.busca.aplicacao.porta.saida.RegistroDeOfertas;
import dev.sugestao.busca.dominio.oferta.Oferta;
import dev.sugestao.busca.dominio.oferta.OfertaInvalida;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class CadastrarOfertaCasoDeUsoTest {

    private static final Instant INICIO = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant FIM = Instant.parse("2027-10-01T00:00:00Z");

    /** Registro em memória que também conta as gravações. */
    private static final class RegistroFalso implements RegistroDeOfertas {
        private final Map<String, Oferta> porId = new LinkedHashMap<>();
        private final List<String> gravacoes = new ArrayList<>();

        @Override
        public void gravar(Oferta oferta) {
            gravacoes.add(oferta.ofertaId());
            porId.put(oferta.ofertaId(), oferta);
        }

        @Override
        public Optional<Oferta> porId(String ofertaId) {
            return Optional.ofNullable(porId.get(ofertaId));
        }
    }

    private final RegistroFalso registro = new RegistroFalso();
    private final CadastrarOfertaCasoDeUso caso = new CadastrarOfertaCasoDeUso(registro);

    private static ComandoDeCadastroDeOferta comando(String ofertaId, String rotulo) {
        return new ComandoDeCadastroDeOferta(
                ofertaId, "consórcio de imóvel", List.of("consorcio"), rotulo,
                "/app/credito/consorcio", "credito", "CONSORCIO_IMOVEL",
                false, true, true, List.of("simulacao_abandonada_24h"),
                List.of("PF"), List.of("VAREJO"), INICIO, FIM);
    }

    /** A jornada dona das ofertas destes testes. */
    private static final Autorizacao DONA = Autorizacao.daJornada("credito");

    @Test
    @DisplayName("primeira gravação reporta criada; a segunda reporta substituída")
    void criadaDepoisSubstituida() {
        var primeira = caso.executar(comando("OFR-X", "primeiro rotulo"), DONA);
        assertThat(primeira.criada()).isTrue();
        assertThat(primeira.ofertaId()).isEqualTo("OFR-X");

        // Idempotente por identificador: a mesma oferta enviada duas vezes
        // resulta numa oferta. É o que permite à jornada reenviar depois de um
        // timeout sem saber se a primeira tentativa chegou.
        var segunda = caso.executar(comando("OFR-X", "rotulo corrigido"), DONA);
        assertThat(segunda.criada()).isFalse();
        assertThat(segunda.oferta().rotulo()).isEqualTo("rotulo corrigido");

        assertThat(registro.porId).hasSize(1);
        assertThat(registro.gravacoes).containsExactly("OFR-X", "OFR-X");
    }

    @Test
    @DisplayName("oferta inválida não chega a tocar o registro")
    void validaAntesDeGravar() {
        // Importa por dois motivos. Um: uma oferta recusada não deve ter
        // custado uma ida ao índice. Dois, e mais grave: ela não deve ter
        // sobrescrito a versão boa que já estava gravada.
        caso.executar(comando("OFR-X", "versao boa"), DONA);
        registro.gravacoes.clear();

        var invalido = new ComandoDeCadastroDeOferta(
                "OFR-X", "consórcio", List.of(), "versao ruim",
                "https://evil.example", "busca", "",
                false, false, false, List.of(), List.of("PF"), List.of("VAREJO"), INICIO, FIM);

        assertThatThrownBy(() -> caso.executar(invalido, DONA)).isInstanceOf(OfertaInvalida.class);

        assertThat(registro.gravacoes).isEmpty();
        assertThat(registro.porId("OFR-X")).get()
                .extracting(Oferta::rotulo).isEqualTo("versao boa");
    }

    @Test
    @DisplayName("devolve a oferta gravada, não a recebida")
    void devolveOEstadoGravado() {
        // O termo volta normalizado e o identificador em maiúscula. Sem isto,
        // quem integra não tem como saber qual das suas grafias sobreviveu — e
        // descobriria mais tarde que a busca por "Consórcio" não encontra nada.
        var resultado = caso.executar(comando("ofr-minusculo", ""), DONA);

        assertThat(resultado.ofertaId()).isEqualTo("OFR-MINUSCULO");
        assertThat(resultado.oferta().termo()).isEqualTo("consorcio de imovel");
    }

    // -----------------------------------------------------------------------
    // Autorização por jornada. A regra mora aqui, na aplicação, e não no
    // controller: ela é regra de negócio, e continuaria valendo se o cadastro
    // passasse a entrar por uma fila, por um lote ou por um console.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("jornada alheia é recusada: seguros não publica oferta de crédito")
    void recusaJornadaAlheia() {
        // Sem esta regra, o escopo ofertas:escrever bastaria para qualquer
        // serviço publicar oferta de qualquer jornada — com rótulo e destino
        // escolhidos por ele — e ela apareceria na busca como se fosse do time
        // de crédito.
        var deSeguros = Autorizacao.daJornada("seguros");

        var erro = catchThrowableOfType(
                () -> caso.executar(comando("OFR-X", ""), deSeguros),
                JornadaNaoAutorizada.class);

        assertThat(erro).isNotNull();
        assertThat(erro.jornadaPedida()).isEqualTo("credito");
        assertThat(erro.jornadaDoChamador()).isEqualTo("seguros");

        // E nada foi gravado.
        assertThat(registro.gravacoes).isEmpty();
    }

    @Test
    @DisplayName("quem não declara jornada nenhuma não cadastra nada")
    void recusaSemJornadaNoToken() {
        for (var sem : List.of(Autorizacao.daJornada(null), Autorizacao.daJornada(""),
                Autorizacao.daJornada("   "))) {

            assertThatThrownBy(() -> caso.executar(comando("OFR-X", ""), sem))
                    .isInstanceOf(JornadaNaoAutorizada.class);
        }
        assertThat(registro.gravacoes).isEmpty();
    }

    @Test
    @DisplayName("a autorização irrestrita cadastra em qualquer jornada")
    void irrestritaCadastraEmQualquerJornada() {
        // Existe para operação e carga inicial de catálogo: alguém precisa poder
        // semear. É privilégio alto e por isso tem nome próprio no escopo.
        var resultado = caso.executar(comando("OFR-X", ""), Autorizacao.irrestrita());
        assertThat(resultado.criada()).isTrue();
    }

    @Test
    @DisplayName("a jornada comparada é a normalizada pelo domínio, não a do corpo")
    void comparaAJornadaNormalizada() {
        // " CRÉDITO " no corpo normaliza para "credito" no domínio, e é com essa
        // que a autorização é conferida. Sem isso, a mesma oferta passaria ou
        // não dependendo de espaço em branco e acento no corpo.
        var comEspacoEAcento = new ComandoDeCadastroDeOferta(
                "OFR-Y", "consórcio", List.of(), "", "/app/x", "  CRÉDITO  ", "CONSORCIO",
                false, false, false, List.of(), List.of("PF"), List.of("VAREJO"), INICIO, FIM);

        assertThat(caso.executar(comEspacoEAcento, DONA).criada()).isTrue();

        assertThatThrownBy(() -> caso.executar(comEspacoEAcento, Autorizacao.daJornada("seguros")))
                .isInstanceOf(JornadaNaoAutorizada.class);
    }

    @Test
    @DisplayName("maiúscula e espaço no token não mudam a decisão")
    void tokenTolerantePorNormalizacao() {
        assertThat(caso.executar(comando("OFR-X", ""), Autorizacao.daJornada("  CREDITO ")).criada())
                .isTrue();
    }

    @Test
    @DisplayName("jornada não é namespace: 'credito' não autoriza 'credito-imobiliario'")
    void jornadaNaoEhPrefixo() {
        var oferta = new ComandoDeCadastroDeOferta(
                "OFR-Z", "financiamento", List.of(), "", "/app/x",
                "credito-imobiliario", "FIN_IMOVEL",
                false, false, false, List.of(), List.of("PF"), List.of("VAREJO"), INICIO, FIM);

        // Se a comparação fosse por prefixo, quem é dono de 'credito' passaria a
        // ser dono de toda jornada cujo nome comece com isso. Hierarquia tem de
        // ser declarada, nunca inferida de casamento de texto.
        assertThatThrownBy(() -> caso.executar(oferta, DONA))
                .isInstanceOf(JornadaNaoAutorizada.class);
    }

    @Test
    @DisplayName("a consulta por identificador tolera minúscula e espaços")
    void consultaNormalizaOIdentificador() {
        caso.executar(comando("OFR-X", ""), DONA);

        assertThat(caso.porId("ofr-x")).isPresent();
        assertThat(caso.porId("  OFR-X  ")).isPresent();
        assertThat(caso.porId("OFR-NAO-EXISTE")).isEmpty();
    }

    @Test
    @DisplayName("identificador vazio ou nulo na consulta não vira ida ao registro")
    void consultaVaziaNaoConsultaORegistro() {
        assertThat(caso.porId(null)).isEmpty();
        assertThat(caso.porId("")).isEmpty();
        assertThat(caso.porId("   ")).isEmpty();
    }
}
