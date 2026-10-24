package dev.sugestao.busca.dominio.evento;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SaneadorTest {

    private final Saneador saneador = new Saneador();

    @ParameterizedTest
    @ValueSource(strings = {
            "pagar boleto",
            "antecipar limite",
            "ver extrato de 2019",
            "simular emprestimo de 37 reais",
            "cdb 2 anos",
            "parcelar fatura em 12x"
    })
    @DisplayName("busca legítima passa, inclusive com número curto")
    void aceitaBuscaLegitima(String termo) {
        assertThat(saneador.avaliar(termo).aceito()).isTrue();
    }

    @Test
    @DisplayName("CPF é barrado, formatado ou não")
    void barraCpf() {
        assertThat(saneador.avaliar("adiantar para cpf 111.444.777-35").motivo()).isEqualTo("CPF");
        assertThat(saneador.avaliar("11144477735").motivo()).isEqualTo("CPF");
    }

    @Test
    @DisplayName("CNPJ é barrado")
    void barraCnpj() {
        assertThat(saneador.avaliar("boleto 11.222.333/0001-81").motivo()).isEqualTo("CNPJ");
    }

    @Test
    @DisplayName("cartão é barrado pela validação de dígito")
    void barraCartaoPorLuhn() {
        assertThat(saneador.avaliar("cartao 4111 1111 1111 1111").motivo()).isEqualTo("CARTAO");
    }

    @Test
    @DisplayName("e-mail e chave Pix aleatória são barrados")
    void barraEmailEChavePix() {
        assertThat(saneador.avaliar("pix para joao@exemplo.com.br").motivo()).isEqualTo("EMAIL");
        assertThat(saneador.avaliar("pix 3f2504e0-4f89-11d3-9a0c-0305e82c3301").motivo())
                .isEqualTo("CHAVE_PIX_ALEATORIA");
    }

    @Test
    @DisplayName("agência e conta caem na regra larga de sequência numérica")
    void barraAgenciaEConta() {
        // Não tem dígito verificador para testar, então quem pega é o corte
        // mais largo. É de propósito que ele seja largo: o custo de um falso
        // positivo é uma sugestão a menos, e o de um falso negativo é o dado de
        // uma pessoa na tela de outra.
        assertThat(saneador.avaliar("consultar saldo da conta 12345 6").motivo())
                .isEqualTo("SEQUENCIA_NUMERICA_LONGA");
        assertThat(saneador.avaliar("transferir para 0341 98765-4").motivo())
                .isEqualTo("SEQUENCIA_NUMERICA_LONGA");
    }

    @Test
    @DisplayName("nome próprio passa — e é por isso que o piso de k-anonimato existe")
    void nomeProprioPassaDeProposito() {
        // Nenhuma expressão regular separa "maria silva" de "maria da penha
        // lei". Este portão não tenta, e tentar por lista de nomes descartaria
        // metade do vocabulário legítimo. Quem pega este caso é o piso de
        // clientes distintos, na publicação.
        assertThat(saneador.avaliar("adiantamento para maria silva").aceito()).isTrue();
        assertThat(saneador.avaliar("maria da penha lei").aceito()).isTrue();
    }

    @Test
    @DisplayName("termo vazio não entra")
    void barraTermoVazio() {
        assertThat(saneador.avaliar("").aceito()).isFalse();
        assertThat(saneador.avaliar(null).aceito()).isFalse();
    }
}
