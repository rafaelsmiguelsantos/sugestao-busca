package dev.sugestao.busca.aplicacao.casodeuso;

import dev.sugestao.busca.aplicacao.ParametrosDeSugestao;
import dev.sugestao.busca.aplicacao.Prazos;
import dev.sugestao.busca.aplicacao.RastroDaSugestao;
import dev.sugestao.busca.aplicacao.porta.entrada.ConsultaDeSugestao;
import dev.sugestao.busca.aplicacao.porta.saida.CatalogoDeSugestoes;
import dev.sugestao.busca.aplicacao.porta.saida.MedidorDeBusca;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.Candidato;
import dev.sugestao.busca.dominio.ClassificadorDeOrigem;
import dev.sugestao.busca.dominio.Cliente;
import dev.sugestao.busca.dominio.Cotas;
import dev.sugestao.busca.dominio.Descarte;
import dev.sugestao.busca.dominio.MotivoDeDescarte;
import dev.sugestao.busca.dominio.Misturador;
import dev.sugestao.busca.dominio.Origem;
import dev.sugestao.busca.dominio.Peneira;
import dev.sugestao.busca.dominio.Procedencia;
import dev.sugestao.busca.dominio.Rankeador;
import dev.sugestao.busca.dominio.Recuperador;
import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.Restricoes;
import dev.sugestao.busca.dominio.Segmento;
import dev.sugestao.busca.dominio.SituacaoDaConta;
import dev.sugestao.busca.dominio.Substituto;
import dev.sugestao.busca.dominio.Substitutos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * O caso de uso inteiro, sem Spring, sem Solr e sem rede. É o que a separação de
 * camadas compra: cada degradação do desenho vira um teste de milissegundos.
 */
class SugerirSugestoesCasoDeUsoTest {

    private static final Cliente CLIENTE = new Cliente("c-1", Segmento.PF);

    /**
     * Prazos largos, para os testes que são sobre <em>regra</em>.
     *
     * <p>Os 18 ms de produção não cabem num teste de lógica. O lambda que faz
     * as vezes do índice responde em microssegundos, mas agendar a thread
     * virtual e carregar as classes na primeira execução não — e aí o prazo
     * estoura, o caminho alternativo entra, e o teste falha dizendo que a
     * normalização não aconteceu quando o que houve foi escalonamento. Teste
     * que mede tempo de máquina sem querer é teste que falha sozinho às
     * sextas-feiras.
     */
    private static final ParametrosDeSugestao PARAMETROS = new ParametrosDeSugestao(
            2, 20, Cotas.padrao(), Prazos.de(2000, 2000, 3000));

    /**
     * Índice folgado, perfil apertado. É a forma de provocar <em>de propósito</em>
     * a degradação do perfil: o que se mede aqui é a decisão do caso de uso
     * quando o prazo vence, não a velocidade da máquina.
     */
    private static final ParametrosDeSugestao PARAMETROS_COM_PERFIL_APERTADO =
            new ParametrosDeSugestao(2, 20, Cotas.padrao(), Prazos.de(2000, 8, 3000));

    @Test
    @DisplayName("prefixo curto demais não chega a tocar em nenhuma dependência")
    void prefixoCurtoNaoConsultaNada() {
        var indiceTocado = new AtomicBoolean(false);
        var perfilTocado = new AtomicBoolean(false);

        var caso = montar(
                (prefixo, segmento, limite) -> {
                    indiceTocado.set(true);
                    return CatalogoDeSugestoes.Busca.exata(List.of());
                },
                id -> {
                    perfilTocado.set(true);
                    return Perfil.NEUTRO;
                });

        var lista = caso.executar(new ConsultaDeSugestao("a", CLIENTE)).lista();

        assertThat(lista.itens()).isEmpty();
        assertThat(indiceTocado).isFalse();
        assertThat(perfilTocado).isFalse();
    }

    @Test
    @DisplayName("perfil que estoura o prazo vira lista popular, marcada como degradada")
    void perfilLentoFalhaAberta() {
        var caso = montar(
                (prefixo, segmento, limite) -> CatalogoDeSugestoes.Busca.exata(List.of(popular("pagar boleto"))),
                id -> {
                    dormir(200);
                    return new Perfil(List.of("pix agendado"), Set.of(), Set.of(), Set.of(), SituacaoDaConta.ATIVA, true);
                },
                PARAMETROS_COM_PERFIL_APERTADO);

        var lista = caso.executar(new ConsultaDeSugestao("pa", CLIENTE)).lista();

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
                    dormir(120);
                    return CatalogoDeSugestoes.Busca.exata(List.of(popular("pagar boleto")));
                },
                id -> new Perfil(List.of("pagar pix"), Set.of(), Set.of(), Set.of(), SituacaoDaConta.ATIVA, true),
                PARAMETROS_COM_PERFIL_APERTADO);

        var lista = caso.executar(new ConsultaDeSugestao("pa", CLIENTE)).lista();

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
                id -> new Perfil(List.of("pagar boleto"), Set.of(), Set.of(), Set.of(), SituacaoDaConta.ATIVA, true));

        var lista = caso.executar(new ConsultaDeSugestao("pa", CLIENTE)).lista();

        assertThat(lista.itens()).singleElement()
                .satisfies(s -> assertThat(s.origem()).isEqualTo(Origem.HISTORICO));
    }

    @Test
    @DisplayName("gatilho ativo promove o candidato a evento e o põe na primeira linha")
    void gatilhoAtivoViraEvento() {
        var comGatilho = new Candidato("antecipação de limite", "pela sua última tentativa de Pix",
                "/app/credito/antecipacao", "credito", "ANTECIPACAO", Restricoes.NENHUMA, List.of("pix_recusado_72h"), 5000, 1.0, Origem.CATALOGO);

        var caso = montar(
                (prefixo, segmento, limite) -> CatalogoDeSugestoes.Busca.exata(List.of(popular("antecipar agora"), comGatilho)),
                id -> new Perfil(List.of(), Set.of("pix_recusado_72h"), Set.of(), Set.of(), SituacaoDaConta.ATIVA, true));

        var lista = caso.executar(new ConsultaDeSugestao("ante", CLIENTE)).lista();

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
                    return CatalogoDeSugestoes.Busca.exata(List.of());
                },
                id -> Perfil.NEUTRO);

        caso.executar(new ConsultaDeSugestao("  ANTECIPAÇÃO  ", CLIENTE));

        assertThat(recebido.get()).isEqualTo("antecipacao");
    }

    @Test
    @DisplayName("o rastro diz por que cada candidato ficou de fora, e quanto cada etapa custou")
    void rastroRegistraAsDecisoes() {
        var exigeElegibilidade = new Candidato("crédito consignado", "", "/app/c", "credito",
                "CONSIGNADO", new Restricoes(true, false, false), List.of(), 5000, 1.0, Origem.CATALOGO);
        var abaixoDoPiso = new Candidato("pagar boleto do joao", "", "/busca", "busca", "", Restricoes.NENHUMA, List.of(), 4, 1.0, Origem.POPULAR);

        var caso = montar(
                (prefixo, segmento, limite) -> CatalogoDeSugestoes.Busca.exata(List.of(exigeElegibilidade, abaixoDoPiso, popular("pagar boleto"))),
                id -> new Perfil(List.of(), Set.of(), Set.of(), Set.of(), SituacaoDaConta.ATIVA, true));

        var rastro = caso.executar(new ConsultaDeSugestao("pa", CLIENTE)).rastro();

        assertThat(rastro.prefixoNormalizado()).isEqualTo("pa");
        assertThat(rastro.candidatosDoIndice()).isEqualTo(3);
        assertThat(rastro.perfil()).isEqualTo(RastroDaSugestao.EstadoDoPerfil.ENCONTRADO);
        assertThat(rastro.msTotal()).isNotNegative();

        assertThat(rastro.descartes())
                .extracting(Descarte::termo, Descarte::motivo)
                .containsExactlyInAnyOrder(
                        tuple("crédito consignado", MotivoDeDescarte.SEM_ELEGIBILIDADE),
                        tuple("pagar boleto do joao", MotivoDeDescarte.ABAIXO_DO_PISO));

        assertThat(rastro.linhas()).singleElement()
                .satisfies(linha -> {
                    assertThat(linha.posicao()).isZero();
                    assertThat(linha.termo()).isEqualTo("pagar boleto");
                    assertThat(linha.origem()).isEqualTo(Origem.POPULAR);
                });
    }

    @Test
    @DisplayName("o rastro distingue perfil inexistente de perfil que estourou o prazo")
    void rastroDistingueAsDegradacoes() {
        var semPerfil = montar(
                (prefixo, segmento, limite) -> CatalogoDeSugestoes.Busca.exata(List.of(popular("pagar boleto"))),
                id -> Perfil.NEUTRO);

        var lento = montar(
                (prefixo, segmento, limite) -> CatalogoDeSugestoes.Busca.exata(List.of(popular("pagar boleto"))),
                id -> {
                    dormir(200);
                    return new Perfil(List.of(), Set.of(), Set.of(), Set.of(), SituacaoDaConta.ATIVA, true);
                },
                PARAMETROS_COM_PERFIL_APERTADO);

        assertThat(semPerfil.executar(new ConsultaDeSugestao("pa", CLIENTE)).rastro().perfil())
                .isEqualTo(RastroDaSugestao.EstadoDoPerfil.SEM_PERFIL);

        assertThat(lento.executar(new ConsultaDeSugestao("pa", CLIENTE)).rastro().perfil())
                .isEqualTo(RastroDaSugestao.EstadoDoPerfil.PRAZO_VENCIDO);
    }

    // -----------------------------------------------------------------------
    // A linha substituta. Ver Recuperador.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("oferta removida por elegibilidade devolve a linha da jornada em vez de lista vazia")
    void recuperaListaZeradaPelaPeneira() {
        // É o caso medido contra o índice real: cliente novo digita "veiculos",
        // a busca encontra "financiamento de veículo", e a peneira o remove —
        // com razão. Antes disto, a resposta era lista vazia.
        var financiamento = new Candidato("OFR-CREDITO-FIN-VEICULO", "financiamento de veículo",
                "", "/app/credito/financiamento-de-veiculo", "credito", "FINANCIAMENTO_VEICULO",
                new Restricoes(true, true, false), List.of(), 0, 3.2,
                Origem.CATALOGO, Procedencia.CATALOGO);

        var caso = montar(
                (prefixo, segmento, limite) -> CatalogoDeSugestoes.Busca.exata(List.of(financiamento)),
                id -> Perfil.NEUTRO,
                PARAMETROS,
                comSubstitutaDeCredito());

        var resultado = caso.executar(new ConsultaDeSugestao("veiculos", CLIENTE));
        var lista = resultado.lista();

        assertThat(lista.itens()).singleElement().satisfies(item -> {
            assertThat(item.termo()).isEqualTo("simular credito");
            assertThat(item.origem()).isEqualTo(Origem.SUBSTITUTO);
            assertThat(item.jornada()).isEqualTo("credito");

            // Nunca é oferta: sem identificador e sem produto, a jornada
            // consumidora não recebe um identificador que ela não tem como
            // acionar.
            assertThat(item.ehOferta()).isFalse();
        });

        assertThat(lista.melhorOferta()).isEmpty();

        var rastro = resultado.rastro();
        assertThat(rastro.substituida()).isTrue();
        assertThat(rastro.jornadaSubstituta()).isEqualTo("credito");

        // E não é zero-resultado: a busca foi atendida e entregou uma linha.
        assertThat(rastro.zeroResultado()).isFalse();
    }

    @Test
    @DisplayName("lista vazia porque nada casou continua vazia: não há jornada a oferecer")
    void naoInventaLinhaQuandoNadaCasou() {
        // A regra que mantém o recurso honesto. Sem ela, qualquer prefixo sem
        // resultado passaria a devolver uma linha de ajuda qualquer — e aí o
        // recurso deixaria de resgatar busca e passaria a mascarar falha de
        // cobertura, que é a métrica mais importante do projeto.
        var caso = montar(
                (prefixo, segmento, limite) -> CatalogoDeSugestoes.Busca.exata(List.of()),
                id -> Perfil.NEUTRO,
                PARAMETROS,
                comSubstitutaDeCredito());

        var resultado = caso.executar(new ConsultaDeSugestao("xpto", CLIENTE));

        assertThat(resultado.lista().itens()).isEmpty();
        assertThat(resultado.rastro().substituida()).isFalse();
        assertThat(resultado.rastro().zeroResultado()).isTrue();
    }

    @Test
    @DisplayName("termo barrado pelo piso de k-anonimato não gera substituta — a linha seria o vazamento")
    void pisoDeKAnonimatoNaoGeraSubstituta() {
        // A exclusão que mais importa em MotivoDeDescarte.condicionalDoCliente().
        // Se o piso gerasse substituta, a presença da linha informaria que ALGO
        // casou com aquele prefixo — e esconder exatamente isso é a única coisa
        // que o piso faz.
        //
        // A jornada 'credito' num termo de log é artificial: na prática o log
        // publica tudo em Jornada.AUSENTE, e com isso a substituta nem seria
        // procurada. Está assim de propósito, para o teste medir a regra do
        // motivo e não um efeito colateral do dado.
        var abaixoDoPiso = new Candidato("", "antecipar limite da maria", "", "/busca",
                "credito", "", Restricoes.NENHUMA, List.of(), 4, 1.0,
                Origem.POPULAR, Procedencia.LOG);

        var caso = montar(
                (prefixo, segmento, limite) -> CatalogoDeSugestoes.Busca.exata(List.of(abaixoDoPiso)),
                id -> Perfil.NEUTRO,
                PARAMETROS,
                comSubstitutaDeCredito());

        var resultado = caso.executar(new ConsultaDeSugestao("ante", CLIENTE));

        assertThat(resultado.lista().itens()).isEmpty();
        assertThat(resultado.rastro().substituida()).isFalse();
    }

    @Test
    @DisplayName("a substituta não completa lista curta: ela só entra quando não sobrou nada")
    void naoCompletaListaCurta() {
        var umaLinhaBoa = popular("antecipação de limite");
        var removida = new Candidato("OFR-CREDITO-CONSIGNADO", "crédito consignado", "",
                "/app/credito/consignado", "credito", "CONSIGNADO",
                new Restricoes(true, false, false), List.of(), 0, 1.0,
                Origem.CATALOGO, Procedencia.CATALOGO);

        var caso = montar(
                (prefixo, segmento, limite) ->
                        CatalogoDeSugestoes.Busca.exata(List.of(umaLinhaBoa, removida)),
                id -> Perfil.NEUTRO,
                PARAMETROS,
                comSubstitutaDeCredito());

        var lista = caso.executar(new ConsultaDeSugestao("ante", CLIENTE)).lista();

        // Uma linha boa mais uma de ajuda é uma lista pior que uma linha boa: a
        // segunda rouba atenção de quem já tinha o que queria.
        assertThat(lista.itens()).extracting(s -> s.termo()).containsExactly("antecipação de limite");
    }

    private static Recuperador comSubstitutaDeCredito() {
        return new Recuperador(new Substitutos(List.of(
                new Substituto("credito", "simular credito", "Veja as condicoes e simule",
                        "/app/credito/simular"))));
    }

    // -----------------------------------------------------------------------

    private SugerirSugestoesCasoDeUso montar(CatalogoDeSugestoes catalogo, RepositorioDePerfil perfis) {
        return montar(catalogo, perfis, PARAMETROS);
    }

    private SugerirSugestoesCasoDeUso montar(
            CatalogoDeSugestoes catalogo, RepositorioDePerfil perfis, ParametrosDeSugestao parametros) {
        // Sem substitutas: é o comportamento anterior ao Recuperador, e é o que
        // todos os testes acima estão medindo.
        return montar(catalogo, perfis, parametros, new Recuperador(Substitutos.NENHUM));
    }

    private SugerirSugestoesCasoDeUso montar(
            CatalogoDeSugestoes catalogo, RepositorioDePerfil perfis,
            ParametrosDeSugestao parametros, Recuperador recuperador) {
        return new SugerirSugestoesCasoDeUso(
                catalogo, perfis, new ClassificadorDeOrigem(), new Peneira(30),
                new Rankeador(), new Misturador(), recuperador, parametros, MedidorDeBusca.NULO);
    }

    private static Candidato popular(String termo) {
        return new Candidato(termo, "", "/busca?q=" + termo, "busca", "", Restricoes.NENHUMA, List.of(), 1000, 1.0, Origem.POPULAR);
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
