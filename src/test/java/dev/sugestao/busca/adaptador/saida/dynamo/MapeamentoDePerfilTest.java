package dev.sugestao.busca.adaptador.saida.dynamo;

import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.SituacaoDaConta;
import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O mapeamento, testado sem banco no ar.
 *
 * <p>É o que faz a suíte continuar rodando offline depois de o projeto ganhar uma
 * dependência de rede. E não é só conveniência: o mapeamento é onde moram os
 * defeitos desta integração — campo esquecido, vazio confundido com ausente,
 * número com vírgula decimal. Nada disso precisa de DynamoDB para ser pego.
 */
class MapeamentoDePerfilTest {

    @Test
    @DisplayName("sem item nenhum, devolve o perfil NEUTRO - e nao um perfil vazio 'conhecido'")
    void semItemDevolveNeutro() {
        assertThat(MapeamentoDePerfil.perfilDeItens(List.of())).isEqualTo(Perfil.NEUTRO);
        assertThat(MapeamentoDePerfil.perfilDeItens(null)).isEqualTo(Perfil.NEUTRO);

        // A diferenca importa: conhecido=false e o que faz a lista sair generica
        // em vez de personalizada, sem ninguem tratar nulo.
        assertThat(Perfil.NEUTRO.conhecido()).isFalse();
    }

    @Test
    @DisplayName("os dois itens se juntam num perfil, cada campo vindo do seu dono")
    void juntaOsDoisItens() {
        var comportamento = Map.of(
                MapeamentoDePerfil.SK, AttributeValue.fromS(MapeamentoDePerfil.SK_COMPORTAMENTO),
                "historico", MapeamentoDePerfil.listaDeTextos(List.of("pix", "emprestimo")),
                "afinidadePorJornada", MapeamentoDePerfil.mapaDeNumeros(Map.of("credito", 0.47)),
                "fadigaPorTermo", MapeamentoDePerfil.mapaDeNumeros(Map.of("cdb", 0.6)));

        var cadastro = Map.of(
                MapeamentoDePerfil.SK, AttributeValue.fromS(MapeamentoDePerfil.SK_CADASTRO),
                "elegibilidades", MapeamentoDePerfil.listaDeTextos(List.of("EMPRESTIMO")),
                "produtosContratados", MapeamentoDePerfil.listaDeTextos(List.of("CDB")),
                "situacaoDaConta", AttributeValue.fromS("INADIMPLENTE"));

        var perfil = MapeamentoDePerfil.perfilDeItens(List.of(comportamento, cadastro));

        assertThat(perfil.historico()).containsExactly("pix", "emprestimo");
        assertThat(perfil.afinidadePorJornada()).containsEntry("credito", 0.47);
        assertThat(perfil.fadigaPorTermo()).containsEntry("cdb", 0.6);
        assertThat(perfil.elegibilidades()).containsExactly("EMPRESTIMO");
        assertThat(perfil.produtosContratados()).containsExactly("CDB");
        assertThat(perfil.situacaoDaConta()).isEqualTo(SituacaoDaConta.INADIMPLENTE);
        assertThat(perfil.conhecido()).isTrue();
    }

    @Test
    @DisplayName("a ordem dos itens na Query nao importa")
    void ordemDosItensNaoImporta() {
        var comportamento = Map.of(
                MapeamentoDePerfil.SK, AttributeValue.fromS(MapeamentoDePerfil.SK_COMPORTAMENTO),
                "historico", MapeamentoDePerfil.listaDeTextos(List.of("pix")));
        var cadastro = Map.of(
                MapeamentoDePerfil.SK, AttributeValue.fromS(MapeamentoDePerfil.SK_CADASTRO),
                "situacaoDaConta", AttributeValue.fromS("ATIVA"));

        var numaOrdem = MapeamentoDePerfil.perfilDeItens(List.of(comportamento, cadastro));
        var naOutra = MapeamentoDePerfil.perfilDeItens(List.of(cadastro, comportamento));

        assertThat(numaOrdem).isEqualTo(naOutra);
    }

    @Test
    @DisplayName("so o item de COMPORTAMENTO: os fatos de cadastro saem vazios e seguros")
    void soComportamento() {
        var perfil = MapeamentoDePerfil.perfilDeItens(List.of(Map.of(
                MapeamentoDePerfil.SK, AttributeValue.fromS(MapeamentoDePerfil.SK_COMPORTAMENTO),
                "historico", MapeamentoDePerfil.listaDeTextos(List.of("pix")))));

        assertThat(perfil.historico()).containsExactly("pix");
        assertThat(perfil.elegibilidades()).isEmpty();
        assertThat(perfil.produtosContratados()).isEmpty();

        // DESCONHECIDA e a direcao segura: desconhecida nao e operante, entao a
        // oferta condicionada nao aparece ate alguem que saiba dizer.
        assertThat(perfil.situacaoDaConta()).isEqualTo(SituacaoDaConta.DESCONHECIDA);
    }

    @Test
    @DisplayName("situacao de conta que o enum nao conhece cai em DESCONHECIDA, nao estoura")
    void situacaoDesconhecidaNaoEstoura() {
        var perfil = MapeamentoDePerfil.perfilDeItens(List.of(Map.of(
                MapeamentoDePerfil.SK, AttributeValue.fromS(MapeamentoDePerfil.SK_CADASTRO),
                "situacaoDaConta", AttributeValue.fromS("EM_ANALISE_CAUTELAR"))));

        // Um valor novo no enum do produtor nao deve derrubar a busca de ninguem.
        assertThat(perfil.situacaoDaConta()).isEqualTo(SituacaoDaConta.DESCONHECIDA);
    }

    @Test
    @DisplayName("item de outro tipo na mesma particao e ignorado, nao quebra a leitura")
    void itemDeOutroTipoEIgnorado() {
        var evento = Map.of(
                MapeamentoDePerfil.SK, AttributeValue.fromS("2026-10-28T12:00:00Z#1"),
                "tipo", AttributeValue.fromS("BUSCA_DIGITADA"));
        var comportamento = Map.of(
                MapeamentoDePerfil.SK, AttributeValue.fromS(MapeamentoDePerfil.SK_COMPORTAMENTO),
                "historico", MapeamentoDePerfil.listaDeTextos(List.of("pix")));

        var perfil = MapeamentoDePerfil.perfilDeItens(List.of(evento, comportamento));

        assertThat(perfil.historico()).containsExactly("pix");
    }

    // ------------------------------------------------------------------ escrita

    @Test
    @DisplayName("HISTORICO VAZIO NAO VIRA ATRIBUTO - e esta e a regra que impede um apagamento")
    void historicoVazioNaoEGravado() {
        var semHistorico = new ComportamentoDoCliente(
                "pf-novo", List.of(), Set.of(),
                Map.of("credito", 0.5), Map.of(), Map.of(), Map.of());

        var atributos = MapeamentoDePerfil.atributosDeComportamento(semHistorico);

        // Vazio significa "esta janela nao teve nada a dizer sobre esta
        // dimensao", nunca "esta pessoa nao tem nada". Gravar o vazio apagaria o
        // historico guardado: um cliente que clicou e nao digitou em 24 h
        // perderia a camada HISTORICO, para sempre, e o unico sintoma seria a
        // lista dele ficar mais pobre.
        assertThat(atributos).doesNotContainKey("historico");

        // E as afinidades SOBRESCREVEM, inclusive com vazio: o decaimento mora
        // dentro do calculo, entao mapa vazio quer dizer afinidade zero de
        // verdade. As duas regras sao opostas de proposito.
        assertThat(atributos).containsKey("afinidadePorJornada");
        assertThat(atributos).containsKey("fadigaPorTermo");
    }

    @Test
    @DisplayName("historico presente e gravado")
    void historicoPresenteEGravado() {
        var comHistorico = new ComportamentoDoCliente(
                "pf-novo", List.of("pix"), Set.of(),
                Map.of(), Map.of(), Map.of(), Map.of());

        assertThat(MapeamentoDePerfil.atributosDeComportamento(comHistorico))
                .containsKey("historico");
    }

    @Test
    @DisplayName("numero vai com ponto decimal, nunca com virgula")
    void numeroComPontoDecimal() {
        var mapa = MapeamentoDePerfil.mapaDeNumeros(Map.of("credito", 0.47));

        // Formatacao com locale pt-BR produziria "0,47", o DynamoDB recusaria o
        // numero, e o erro sairia longe daqui.
        assertThat(mapa.m().get("credito").n()).isEqualTo("0.47").doesNotContain(",");
    }

    @Test
    @DisplayName("conjunto vazio vai como LISTA vazia, porque o DynamoDB recusa SS vazio")
    void conjuntoVazioVaiComoListaVazia() {
        var vazio = MapeamentoDePerfil.listaDeTextos(Set.of());

        assertThat(vazio.l()).isEmpty();
        assertThat(vazio.ss()).isEmpty();
    }

    @Test
    @DisplayName("ida e volta preserva o que foi gravado")
    void idaEVolta() {
        var original = new ComportamentoDoCliente(
                "pf-uniclass",
                List.of("investimentos", "cdb"),
                Set.of(),
                Map.of("investimento", 0.82),
                Map.of("CDB", 0.4, "LCI", 0.91),
                Map.of("investimento", 1.12),
                Map.of("tesouro direto", 0.75));

        var atributos = MapeamentoDePerfil.atributosDeComportamento(original);
        var item = new java.util.LinkedHashMap<String, AttributeValue>(atributos);
        item.put(MapeamentoDePerfil.SK, AttributeValue.fromS(MapeamentoDePerfil.SK_COMPORTAMENTO));

        var perfil = MapeamentoDePerfil.perfilDeItens(List.of(item));

        assertThat(perfil.historico()).containsExactly("investimentos", "cdb");
        assertThat(perfil.afinidadePorJornada()).containsEntry("investimento", 0.82);
        assertThat(perfil.afinidadePorProduto())
                .containsEntry("CDB", 0.4).containsEntry("LCI", 0.91);
        assertThat(perfil.tendenciaPorJornada()).containsEntry("investimento", 1.12);
        assertThat(perfil.fadigaPorTermo()).containsEntry("tesouro direto", 0.75);
    }

    @Test
    @DisplayName("a chave de particao leva prefixo, para nao colidir com outra entidade")
    void chaveLevaPrefixo() {
        var chave = MapeamentoDePerfil.chave("pf-novo", MapeamentoDePerfil.SK_COMPORTAMENTO);

        assertThat(chave.get(MapeamentoDePerfil.PK).s()).isEqualTo("CLIENTE#pf-novo");
        assertThat(chave.get(MapeamentoDePerfil.SK).s())
                .isEqualTo(MapeamentoDePerfil.SK_COMPORTAMENTO);
    }
}
