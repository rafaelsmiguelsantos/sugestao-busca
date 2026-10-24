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
        var perfil = new Perfil(List.of(), Set.of(), Set.of("CDB"), Set.of(), true);

        var sobrou = peneira.aplicar(
                List.of(Candidatos.produto("CDB", "CDB", false),
                        Candidatos.produto("Tesouro Direto", "TESOURO", false)),
                perfil);

        assertThat(sobrou).extracting(Candidato::termo).containsExactly("Tesouro Direto");
    }

    @Test
    @DisplayName("sem elegibilidade conhecida, a oferta que exige elegibilidade some")
    void falhaFechadaNaElegibilidade() {
        var sobrou = peneira.aplicar(
                List.of(Candidatos.produto("crédito consignado", "CONSIGNADO", true),
                        Candidatos.produto("extrato da conta", "", false)),
                Perfil.NEUTRO);

        assertThat(sobrou).extracting(Candidato::termo).containsExactly("extrato da conta");
    }

    @Test
    @DisplayName("item que exige elegibilidade sem produto é chaveado pelo próprio termo")
    void elegibilidadeSemProdutoUsaOTermo() {
        var candidato = Candidatos.produto("portabilidade de salário", "", true);
        var concedida = new Perfil(List.of(), Set.of(), Set.of(),
                Set.of("portabilidade de salario"), true);

        assertThat(peneira.aplicar(List.of(candidato), Perfil.NEUTRO)).isEmpty();
        assertThat(peneira.aplicar(List.of(candidato), concedida)).hasSize(1);
    }

    @Test
    @DisplayName("o piso de k-anonimato barra o que veio do índice")
    void barraTermoAbaixoDoPiso() {
        var sobrou = peneira.aplicar(
                List.of(Candidatos.doIndice("adiantamento para maria silva", 1.0, 2),
                        Candidatos.doIndice("pagar boleto", 1.0, PISO)),
                Perfil.NEUTRO);

        assertThat(sobrou).extracting(Candidato::termo).containsExactly("pagar boleto");
    }

    @Test
    @DisplayName("o piso não se aplica ao histórico: o dono recebendo o próprio dado não é vazamento")
    void historicoNaoRespondePeloPiso() {
        // Zero clientes distintos: ninguém mais digitou isto, e é exatamente
        // por isso que ele só pode voltar para quem digitou.
        var meuHistorico = new Candidato("consultar saldo da conta 12345 6", "",
                "/busca", "busca", "", false, List.of(), 0, 1.0, Origem.HISTORICO);

        assertThat(peneira.aplicar(List.of(meuHistorico), Perfil.NEUTRO)).hasSize(1);
    }
}
