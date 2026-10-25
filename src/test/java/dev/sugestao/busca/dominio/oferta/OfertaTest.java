package dev.sugestao.busca.dominio.oferta;

import dev.sugestao.busca.dominio.Relacionamento;
import dev.sugestao.busca.dominio.Segmento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class OfertaTest {

    private static final Instant INICIO = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant FIM = Instant.parse("2027-10-01T00:00:00Z");

    /** Uma oferta válida, para cada teste estragar um campo só. */
    private static Oferta valida() {
        return Oferta.de(
                "OFR-CREDITO-CONSORCIO", "consórcio de imóvel",
                List.of("consorcio", "carta de credito"), "sem juros",
                "/app/credito/consorcio", "credito", "CONSORCIO_IMOVEL",
                false, true, true,
                List.of("simulacao_abandonada_24h"),
                List.of("PF"), List.of("VAREJO", "UNICLASS"),
                INICIO, FIM);
    }

    @Test
    @DisplayName("oferta válida normaliza o termo pela mesma função do prefixo digitado")
    void normalizaOTermo() {
        var oferta = valida();

        // Se as duas normalizações divergissem, a oferta entraria no índice sob
        // uma chave que nenhuma busca produz: cadastro aceito, oferta
        // inalcançável, e ninguém saberia por quê.
        assertThat(oferta.termo()).isEqualTo("consorcio de imovel");
        assertThat(oferta.ofertaId()).isEqualTo("OFR-CREDITO-CONSORCIO");
        assertThat(oferta.idDoDocumento()).isEqualTo("ofr-ofr-credito-consorcio");
        assertThat(oferta.segmentos()).containsExactly(Segmento.PF);
        assertThat(oferta.relacionamentos())
                .containsExactlyInAnyOrder(Relacionamento.VAREJO, Relacionamento.UNICLASS);
        assertThat(oferta.restricoes().vetaInadimplencia()).isTrue();
    }

    @Test
    @DisplayName("identificador em minúscula é aceito e normalizado para maiúscula")
    void identificadorVaiParaMaiuscula() {
        var oferta = Oferta.de("ofr-credito-x", "termo de teste", List.of(), "",
                "/app/x", "credito", "produto_x", false, false, false,
                List.of(), List.of("PF"), List.of("VAREJO"), INICIO, FIM);

        assertThat(oferta.ofertaId()).isEqualTo("OFR-CREDITO-X");
        assertThat(oferta.produtoAlvo()).isEqualTo("PRODUTO_X");
    }

    @Test
    @DisplayName("acumula TODAS as violações numa recusa só")
    void acumulaTodasAsViolacoes() {
        // A decisão de projeto central desta classe. Recusar no primeiro
        // problema transformaria a integração numa sequência de tentativas, uma
        // por campo errado, cada uma precisando de um ciclo de deploy do lado
        // de quem integra.
        var erro = catchThrowableOfType(
                () -> Oferta.de(
                        "x",                                    // formato
                        "oferta de teste",
                        List.of(), "",
                        "https://phishing.example/pix",         // destino absoluto
                        "busca",                                // jornada reservada
                        "",                                     // produto obrigatório
                        false, false, false,
                        List.of("GATILHO INVALIDO!"),           // formato
                        List.of("PESSOA_FISICA"),               // fora do domínio
                        List.of("BLACK"),                       // fora do domínio
                        FIM, INICIO),                           // vigência invertida
                OfertaInvalida.class);

        assertThat(erro).isNotNull();
        assertThat(erro.violacoes()).extracting(Violacao::campo)
                .containsExactlyInAnyOrder(
                        "ofertaId", "destino", "jornada", "produtoAlvo",
                        "gatilhos", "segmentos", "relacionamentos", "vigenciaFim");
    }

    @Test
    @DisplayName("destino tem de ser caminho relativo: URL absoluta é redirecionador aberto")
    void recusaDestinoAbsoluto() {
        for (String ruim : List.of("https://evil.example/pix", "http://evil.example",
                "//evil.example/pix", "app/sem-barra")) {

            var erro = catchThrowableOfType(() -> comDestino(ruim), OfertaInvalida.class);

            assertThat(erro)
                    .withFailMessage("destino '%s' deveria ser recusado", ruim)
                    .isNotNull();
            assertThat(erro.violacoes()).extracting(Violacao::campo).contains("destino");
        }

        // E o caminho relativo passa.
        assertThat(comDestino("/app/credito/consorcio").destino()).isEqualTo("/app/credito/consorcio");
    }

    @Test
    @DisplayName("jornada 'busca' é reservada e não pode receber oferta")
    void recusaJornadaReservada() {
        // 'busca' é o balde dos termos sem jornada, e o Rankeador a trata como
        // ausência de jornada. Uma oferta cadastrada nela nunca lideraria
        // jornada nenhuma e nunca receberia afinidade do cliente: pagaria o
        // custo de existir sem receber nenhum dos benefícios.
        var erro = catchThrowableOfType(
                () -> Oferta.de("OFR-X", "termo", List.of(), "", "/app/x", "busca", "PRODUTO",
                        false, false, false, List.of(), List.of("PF"), List.of("VAREJO"), INICIO, FIM),
                OfertaInvalida.class);

        assertThat(erro.violacoes()).singleElement()
                .satisfies(v -> {
                    assertThat(v.campo()).isEqualTo("jornada");
                    assertThat(v.codigo()).isEqualTo(Violacao.RESERVADO);
                });
    }

    @Test
    @DisplayName("oferta sem produto não é oferta")
    void recusaOfertaSemProduto() {
        var erro = catchThrowableOfType(
                () -> Oferta.de("OFR-X", "termo", List.of(), "", "/app/x", "credito", "",
                        false, false, false, List.of(), List.of("PF"), List.of("VAREJO"), INICIO, FIM),
                OfertaInvalida.class);

        assertThat(erro.violacoes()).extracting(Violacao::campo).containsExactly("produtoAlvo");
    }

    @Test
    @DisplayName("segmento e faixa desconhecidos são recusados, não coagidos ao padrão")
    void recusaEnumDesconhecido() {
        // No token, faixa desconhecida cair em VAREJO é degradação aceitável: a
        // lista fica mais pobre, nunca mais rica. No cadastro é o oposto — uma
        // oferta destinada a uma faixa publicada para a base inteira.
        var erro = catchThrowableOfType(
                () -> Oferta.de("OFR-X", "termo", List.of(), "", "/app/x", "credito", "PRODUTO",
                        false, false, false, List.of(),
                        List.of("PF", "PESSOA_JURIDICA"), List.of("VAREJO", "BLACK"), INICIO, FIM),
                OfertaInvalida.class);

        assertThat(erro.violacoes()).extracting(Violacao::campo)
                .containsExactlyInAnyOrder("segmentos", "relacionamentos");

        // A mensagem cita o valor recebido, para quem integra achar o erro.
        assertThat(erro.violacoes()).anySatisfy(v ->
                assertThat(v.mensagem()).contains("PESSOA_JURIDICA"));
    }

    @Test
    @DisplayName("segmento e faixa vazios são recusados: documento sem eles fica invisível")
    void recusaPublicoVazio() {
        var erro = catchThrowableOfType(
                () -> Oferta.de("OFR-X", "termo", List.of(), "", "/app/x", "credito", "PRODUTO",
                        false, false, false, List.of(), List.of(), List.of(), INICIO, FIM),
                OfertaInvalida.class);

        assertThat(erro.violacoes()).extracting(Violacao::campo)
                .containsExactlyInAnyOrder("segmentos", "relacionamentos");
    }

    @Test
    @DisplayName("vigência com fim antes do início é oferta que nunca vale")
    void recusaVigenciaInvertida() {
        var erro = catchThrowableOfType(
                () -> Oferta.de("OFR-X", "termo", List.of(), "", "/app/x", "credito", "PRODUTO",
                        false, false, false, List.of(), List.of("PF"), List.of("VAREJO"), FIM, INICIO),
                OfertaInvalida.class);

        assertThat(erro.violacoes()).singleElement()
                .extracting(Violacao::codigo).isEqualTo(Violacao.ORDEM_INVALIDA);
    }

    @Test
    @DisplayName("vigência já encerrada é aceita: correção retroativa é legítima")
    void aceitaVigenciaNoPassado() {
        // O fq de vigência já a mantém fora da busca. Recusar impediria
        // encerramento antecipado e correção de histórico.
        var passado = Oferta.de("OFR-X", "termo", List.of(), "", "/app/x", "credito", "PRODUTO",
                false, false, false, List.of(), List.of("PF"), List.of("VAREJO"),
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-06-01T00:00:00Z"));

        assertThat(passado.vigenciaFim()).isEqualTo(Instant.parse("2020-06-01T00:00:00Z"));
    }

    @Test
    @DisplayName("sinônimos são normalizados e deduplicados")
    void sinonimosNormalizados() {
        var oferta = Oferta.de("OFR-X", "termo de teste",
                List.of("Consórcio", "consorcio", "CONSORCIO", "  carta de crédito  ", ""),
                "", "/app/x", "credito", "PRODUTO", false, false, false,
                List.of(), List.of("PF"), List.of("VAREJO"), INICIO, FIM);

        assertThat(oferta.sinonimos()).containsExactly("consorcio", "carta de credito");
    }

    @Test
    @DisplayName("campos obrigatórios ausentes são todos reportados")
    void recusaTudoAusente() {
        var erro = catchThrowableOfType(
                () -> Oferta.de(null, null, null, null, null, null, null,
                        false, false, false, null, null, null, null, null),
                OfertaInvalida.class);

        assertThat(erro.violacoes()).extracting(Violacao::campo)
                .containsExactlyInAnyOrder("ofertaId", "termo", "destino", "jornada",
                        "produtoAlvo", "segmentos", "relacionamentos",
                        "vigenciaInicio", "vigenciaFim");
    }

    @Test
    @DisplayName("gatilho fora do formato é recusado; gatilho vazio é ignorado")
    void gatilhos() {
        var erro = catchThrowableOfType(
                () -> Oferta.de("OFR-X", "termo", List.of(), "", "/app/x", "credito", "PRODUTO",
                        false, false, false, List.of("ok_valido", "NAO VALE!"),
                        List.of("PF"), List.of("VAREJO"), INICIO, FIM),
                OfertaInvalida.class);
        assertThat(erro.violacoes()).extracting(Violacao::campo).containsExactly("gatilhos");

        // Vazio e nulo na lista não são erro: são ruído de serializador.
        var limpa = Oferta.de("OFR-X", "termo", List.of(), "", "/app/x", "credito", "PRODUTO",
                false, false, false, List.of("ok_valido", "", "  "),
                List.of("PF"), List.of("VAREJO"), INICIO, FIM);
        assertThat(limpa.gatilhos()).containsExactly("ok_valido");
    }

    @Test
    @DisplayName("termo que normaliza para vazio é recusado")
    void recusaTermoQueNormalizaParaVazio() {
        assertThatThrownBy(() ->
                Oferta.de("OFR-X", "!!! ###", List.of(), "", "/app/x", "credito", "PRODUTO",
                        false, false, false, List.of(), List.of("PF"), List.of("VAREJO"), INICIO, FIM))
                .isInstanceOf(OfertaInvalida.class);
    }

    private static Oferta comDestino(String destino) {
        return Oferta.de("OFR-X", "termo de teste", List.of(), "", destino, "credito", "PRODUTO",
                false, false, false, List.of(), List.of("PF"), List.of("VAREJO"), INICIO, FIM);
    }
}
