package dev.sugestao.busca.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PeneiraTest {

    private static final int PISO = 30;
    private final Peneira peneira = new Peneira(PISO);

    @Test
    @DisplayName("não oferece de novo o produto que o cliente já contratou")
    void removeProdutoContratado() {
        var perfil = new Perfil(List.of(), Set.of(), Set.of("CDB"), Set.of(), SituacaoDaConta.ATIVA, true);

        var sobrou = peneira.aplicar(
                List.of(Candidatos.produto("CDB", "CDB", false),
                        Candidatos.produto("Tesouro Direto", "TESOURO", false)),
                perfil);

        assertThat(sobrou.sobreviventes()).extracting(Candidato::termo).containsExactly("Tesouro Direto");
    }

    @Test
    @DisplayName("sem elegibilidade conhecida, a oferta que exige elegibilidade some")
    void falhaFechadaNaElegibilidade() {
        var sobrou = peneira.aplicar(
                List.of(Candidatos.produto("crédito consignado", "CONSIGNADO", true),
                        Candidatos.produto("extrato da conta", "", false)),
                Perfil.NEUTRO);

        assertThat(sobrou.sobreviventes()).extracting(Candidato::termo).containsExactly("extrato da conta");
    }

    @Test
    @DisplayName("item que exige elegibilidade sem produto é chaveado pelo próprio termo")
    void elegibilidadeSemProdutoUsaOTermo() {
        var candidato = Candidatos.produto("portabilidade de salário", "", true);
        var concedida = new Perfil(List.of(), Set.of(), Set.of(),
                Set.of("portabilidade de salario"), SituacaoDaConta.ATIVA, true);

        assertThat(peneira.aplicar(List.of(candidato), Perfil.NEUTRO).sobreviventes()).isEmpty();
        assertThat(peneira.aplicar(List.of(candidato), concedida).sobreviventes()).hasSize(1);
    }

    @Test
    @DisplayName("conta em atraso não recebe oferta de crédito novo")
    void vetaCreditoNovoParaContaInadimplente() {
        var emprestimo = Candidatos.comRestricoes("empréstimo pessoal", "EMPRESTIMO",
                new Restricoes(false, true, false));
        var renegociar = Candidatos.comRestricoes("renegociar dívida", "RENEGOCIACAO",
                new Restricoes(false, false, false));

        var negativado = new Perfil(List.of(), Set.of(), Set.of(), Set.of(),
                SituacaoDaConta.INADIMPLENTE, true);

        var peneirada = peneira.aplicar(List.of(emprestimo, renegociar), negativado);

        // O crédito novo sai; o caminho que resolve fica. Esconder a busca
        // inteira de quem está em atraso seria a decisão errada.
        assertThat(peneirada.sobreviventes()).extracting(Candidato::termo)
                .containsExactly("renegociar dívida");
        assertThat(peneirada.descartes()).singleElement()
                .extracting(Descarte::motivo).isEqualTo(MotivoDeDescarte.CONTA_INADIMPLENTE);
    }

    @Test
    @DisplayName("o mesmo crédito aparece normalmente para conta ativa")
    void creditoNovoApareceParaContaAtiva() {
        var emprestimo = Candidatos.comRestricoes("empréstimo pessoal", "EMPRESTIMO",
                new Restricoes(false, true, false));
        var ativa = new Perfil(List.of(), Set.of(), Set.of(), Set.of(), SituacaoDaConta.ATIVA, true);

        assertThat(peneira.aplicar(List.of(emprestimo), ativa).sobreviventes()).hasSize(1);
    }

    @Test
    @DisplayName("operação que exige conta operante some com conta bloqueada — e com conta desconhecida")
    void exigeContaOperante() {
        var transferir = Candidatos.comRestricoes("transferência TED", "",
                new Restricoes(false, false, true));

        var bloqueada = new Perfil(List.of(), Set.of(), Set.of(), Set.of(),
                SituacaoDaConta.BLOQUEADA, true);

        assertThat(peneira.aplicar(List.of(transferir), bloqueada).sobreviventes()).isEmpty();

        // Falha fechada: sem perfil, a situação é DESCONHECIDA e a operação
        // condicionada também não aparece.
        assertThat(peneira.aplicar(List.of(transferir), Perfil.NEUTRO).sobreviventes()).isEmpty();

        var ativa = new Perfil(List.of(), Set.of(), Set.of(), Set.of(), SituacaoDaConta.ATIVA, true);
        assertThat(peneira.aplicar(List.of(transferir), ativa).sobreviventes()).hasSize(1);
    }

    @Test
    @DisplayName("o piso de k-anonimato barra o que veio do índice")
    void barraTermoAbaixoDoPiso() {
        var sobrou = peneira.aplicar(
                List.of(Candidatos.doIndice("adiantamento para maria silva", 1.0, 2),
                        Candidatos.doIndice("pagar boleto", 1.0, PISO)),
                Perfil.NEUTRO);

        assertThat(sobrou.sobreviventes()).extracting(Candidato::termo).containsExactly("pagar boleto");
    }

    @Test
    @DisplayName("promover a evento não isenta do piso: o evento é candidato do índice, promovido")
    void eventoTambemRespondePeloPiso() {
        // Regressão. Enquanto EVENTO não contava como vindo do índice, um
        // termo abaixo do piso que casasse com um gatilho era promovido antes
        // da Peneira e passava direto pela rede de segurança — indo parar na
        // primeira linha, que é a vaga reservada ao evento.
        var promovido = new Candidato("adiantamento para maria silva", "", "/busca", "credito", "",
                Restricoes.NENHUMA, List.of("pix_recusado_72h"), 2, 1.0, Origem.EVENTO);

        var peneirada = peneira.aplicar(List.of(promovido), Perfil.NEUTRO);

        assertThat(peneirada.sobreviventes()).isEmpty();
        assertThat(peneirada.descartes()).singleElement()
                .extracting(Descarte::motivo).isEqualTo(MotivoDeDescarte.ABAIXO_DO_PISO);
    }

    @Test
    @DisplayName("oferta de catálogo com zero clientes distintos sobrevive ao piso")
    void ofertaDeCatalogoNaoRespondePeloPiso() {
        // O caso que motivou a separação entre Origem e Procedencia. Uma oferta
        // que uma jornada acabou de cadastrar tem zero clientes distintos,
        // porque ninguém a buscou ainda — e com o piso aplicado a tudo ela
        // ficaria invisível até acumular trinta buscas que nunca aconteceriam,
        // porque ela não aparece.
        //
        // Não há o que anonimizar num nome de produto: não existe sujeito para
        // reidentificar. O piso protege termo digitado por gente.
        var oferta = Candidatos.oferta("consórcio de imóvel", "OFR-CREDITO-CONSORCIO", "CONSORCIO");

        var peneirada = peneira.aplicar(List.of(oferta), Perfil.NEUTRO);

        assertThat(peneirada.sobreviventes()).hasSize(1);
        assertThat(peneirada.descartes()).isEmpty();
    }

    @Test
    @DisplayName("promover oferta de catálogo a evento não passa a aplicar o piso a ela")
    void promocaoPreservaAProcedenciaDoCatalogo() {
        // O espelho de eventoTambemRespondePeloPiso. Promover muda onde a linha
        // aparece, não de onde o dado veio: se a procedência fosse deduzida da
        // origem depois da promoção, EVENTO cairia em LOG — a opção restritiva
        // — e a oferta com gatilho desapareceria justamente na linha que o
        // gatilho conquistou.
        var promovida = Candidatos
                .oferta("antecipação de limite", "OFR-CREDITO-ANTECIPACAO", "ANTECIPACAO")
                .com(Origem.EVENTO);

        assertThat(promovida.procedencia()).isEqualTo(Procedencia.CATALOGO);
        assertThat(peneira.aplicar(List.of(promovida), Perfil.NEUTRO).sobreviventes()).hasSize(1);
    }

    @Test
    @DisplayName("termo do log promovido a evento continua respondendo pelo piso")
    void promocaoPreservaAProcedenciaDoLog() {
        // A outra metade da mesma regra, e a que é controle de privacidade: a
        // promoção não pode ser um jeito de escapar do piso.
        var promovido = Candidatos.doLog("adiantamento para maria silva", 2, "pix_recusado_72h")
                .com(Origem.EVENTO);

        assertThat(promovido.procedencia()).isEqualTo(Procedencia.LOG);

        var peneirada = peneira.aplicar(List.of(promovido), Perfil.NEUTRO);
        assertThat(peneirada.sobreviventes()).isEmpty();
        assertThat(peneirada.descartes()).singleElement()
                .extracting(Descarte::motivo).isEqualTo(MotivoDeDescarte.ABAIXO_DO_PISO);
    }

    @Test
    @DisplayName("o piso não se aplica ao histórico: o dono recebendo o próprio dado não é vazamento")
    void historicoNaoRespondePeloPiso() {
        // Zero clientes distintos: ninguém mais digitou isto, e é exatamente
        // por isso que ele só pode voltar para quem digitou.
        var meuHistorico = new Candidato("consultar saldo da conta 12345 6", "",
                "/busca", "busca", "", Restricoes.NENHUMA, List.of(), 0, 1.0, Origem.HISTORICO);

        assertThat(peneira.aplicar(List.of(meuHistorico), Perfil.NEUTRO).sobreviventes()).hasSize(1);
    }
}
