package dev.sugestao.busca.adaptador.saida.perfil;

import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.SituacaoDaConta;
import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PerfisEmMemoriaTest {

    private static final String CLIENTE = "pf-teste";

    private static Perfil comHistoricoEAfinidade() {
        return new Perfil(
                List.of("emprestimo pessoal", "limite do cartao"),
                Set.of("pix_recusado_72h"),
                Set.of("CDB"),
                Set.of("CONSIGNADO"),
                SituacaoDaConta.INADIMPLENTE,
                Map.of("credito", 1.0, "cartao", 0.5),
                true);
    }

    @Test
    @DisplayName("histórico vazio NÃO apaga o histórico guardado")
    void historicoVazioNaoApaga() {
        // Regressão de perda de dado permanente.
        //
        // A agregação devolve um cliente quando ele teve QUALQUER evento na
        // janela, e monta cada dimensão só do que viu. Um cliente que clicou
        // numa sugestão e não digitou nada em 24 h chegava com histórico vazio,
        // e o vazio era gravado sobre o que estava guardado. Um clique apagava a
        // camada HISTORICO da pessoa, para sempre, e o único sintoma era a lista
        // dela ficar mais pobre.
        var perfis = new PerfisEmMemoria(Map.of(CLIENTE, comHistoricoEAfinidade()));

        // Só afinidade: foi o que a janela viu.
        perfis.atualizar(List.of(new ComportamentoDoCliente(
                CLIENTE, List.of(), Set.of(), Map.of("investimento", 1.0))));

        assertThat(perfis.porCliente(CLIENTE).historico())
                .containsExactly("emprestimo pessoal", "limite do cartao");

        // E a afinidade, que a janela TINHA, foi atualizada.
        assertThat(perfis.porCliente(CLIENTE).afinidadeCom("investimento")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("afinidade vazia APAGA — e aqui a assimetria com o histórico é deliberada")
    void afinidadeVaziaApaga() {
        // Esta linha já foi as duas coisas erradas, e a terceira versão é a
        // certa por um motivo que não estava disponível antes.
        //
        // Primeiro ela sobrescrevia com vazio e não havia decaimento: uma busca
        // sem clique apagava a afinidade inteira, e vazio significava apenas
        // "esta janela não viu clique". Depois passou a preservar, e nada
        // esfriava nunca — interesse de um ano atrás continuava inclinando a
        // lista.
        //
        // Agora a afinidade tem janela de 90 dias com meia-vida de 7, e o
        // decaimento mora DENTRO do cálculo, por idade de evento. O que chega
        // aqui já é a verdade atual, e mapa vazio significa "nenhum clique em
        // três meses" — que é afinidade zero de verdade. Sobrescrever voltou a
        // ser o certo porque o vazio voltou a querer dizer algo.
        //
        // O histórico continua protegido, e a assimetria é o ponto: ele é
        // acumulado ("o que você digitou ultimamente"), ela é medida ("em que
        // você demonstrou interesse").
        var perfis = new PerfisEmMemoria(Map.of(CLIENTE, comHistoricoEAfinidade()));

        perfis.atualizar(List.of(new ComportamentoDoCliente(
                CLIENTE, List.of("consorcio"), Set.of(), Map.of())));

        assertThat(perfis.porCliente(CLIENTE).afinidadeCom("credito")).isZero();
        assertThat(perfis.porCliente(CLIENTE).historico()).containsExactly("consorcio");
    }

    @Test
    @DisplayName("a escrita preserva o que a agregação de busca não conhece")
    void preservaOQueNaoEDaBusca() {
        // Gatilho, elegibilidade, produto contratado e situação da conta vêm de
        // outros produtores. Sobrescrever era o bug silencioso óbvio: uma busca
        // qualquer apagaria a inadimplência e o crédito novo voltaria a ser
        // oferecido na tecla seguinte.
        var perfis = new PerfisEmMemoria(Map.of(CLIENTE, comHistoricoEAfinidade()));

        perfis.atualizar(List.of(new ComportamentoDoCliente(
                CLIENTE, List.of("outra busca"), Set.of(), Map.of("conta", 1.0))));

        var depois = perfis.porCliente(CLIENTE);
        assertThat(depois.situacaoDaConta()).isEqualTo(SituacaoDaConta.INADIMPLENTE);
        assertThat(depois.gatilhosAtivos()).containsExactly("pix_recusado_72h");
        assertThat(depois.produtosContratados()).containsExactly("CDB");
        assertThat(depois.elegibilidades()).containsExactly("CONSIGNADO");
    }

    @Test
    @DisplayName("cliente novo entra com situação de conta DESCONHECIDA")
    void clienteNovoFalhaFechada() {
        var perfis = new PerfisEmMemoria(Map.of());

        perfis.atualizar(List.of(new ComportamentoDoCliente(
                "novo", List.of("pix"), Set.of(), Map.of())));

        // Visto só pela busca, a situação não é conhecida — e o lado seguro
        // disso é DESCONHECIDA: a oferta condicionada não aparece até alguém que
        // saiba dizer.
        var perfil = perfis.porCliente("novo");
        assertThat(perfil.situacaoDaConta()).isEqualTo(SituacaoDaConta.DESCONHECIDA);
        assertThat(perfil.conhecido()).isTrue();
        assertThat(perfil.historico()).containsExactly("pix");
    }
}
