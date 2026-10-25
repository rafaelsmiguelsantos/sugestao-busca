package dev.sugestao.busca.aplicacao.casodeuso;

import dev.sugestao.busca.aplicacao.porta.entrada.ComandoDeCadastroDeOferta;
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

    @Test
    @DisplayName("primeira gravação reporta criada; a segunda reporta substituída")
    void criadaDepoisSubstituida() {
        var primeira = caso.executar(comando("OFR-X", "primeiro rotulo"));
        assertThat(primeira.criada()).isTrue();
        assertThat(primeira.ofertaId()).isEqualTo("OFR-X");

        // Idempotente por identificador: a mesma oferta enviada duas vezes
        // resulta numa oferta. É o que permite à jornada reenviar depois de um
        // timeout sem saber se a primeira tentativa chegou.
        var segunda = caso.executar(comando("OFR-X", "rotulo corrigido"));
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
        caso.executar(comando("OFR-X", "versao boa"));
        registro.gravacoes.clear();

        var invalido = new ComandoDeCadastroDeOferta(
                "OFR-X", "consórcio", List.of(), "versao ruim",
                "https://evil.example", "busca", "",
                false, false, false, List.of(), List.of("PF"), List.of("VAREJO"), INICIO, FIM);

        assertThatThrownBy(() -> caso.executar(invalido)).isInstanceOf(OfertaInvalida.class);

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
        var resultado = caso.executar(comando("ofr-minusculo", ""));

        assertThat(resultado.ofertaId()).isEqualTo("OFR-MINUSCULO");
        assertThat(resultado.oferta().termo()).isEqualTo("consorcio de imovel");
    }

    @Test
    @DisplayName("a consulta por identificador tolera minúscula e espaços")
    void consultaNormalizaOIdentificador() {
        caso.executar(comando("OFR-X", ""));

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
