package dev.sugestao.busca.dominio;

import java.util.List;
import java.util.Set;

/**
 * O comportamento e o estado de conta do cliente, montados fora da requisição e
 * lidos em 1 a 5 ms. Nada aqui é calculado no caminho de 30 ms.
 *
 * <p>O {@link #NEUTRO} é o caminho alternativo quando a leitura estoura o prazo,
 * e ele codifica as degradações do desenho de uma vez: sem histórico e sem
 * gatilho, a lista vira a popular do segmento — <em>falha aberta</em>; sem
 * elegibilidade concedida e com a conta em estado desconhecido, toda oferta que
 * depende do perfil some — <em>falha fechada</em>. As duas direções estão
 * certas: uma lista pior não machuca ninguém, uma oferta que o cliente não pode
 * contratar machuca.
 */
public record Perfil(
        List<String> historico,
        Set<String> gatilhosAtivos,
        Set<String> produtosContratados,
        Set<String> elegibilidades,
        SituacaoDaConta situacaoDaConta,
        boolean conhecido) {

    public static final Perfil NEUTRO = new Perfil(
            List.of(), Set.of(), Set.of(), Set.of(), SituacaoDaConta.DESCONHECIDA, false);

    public Perfil {
        historico = historico == null ? List.of() : List.copyOf(historico);
        gatilhosAtivos = gatilhosAtivos == null ? Set.of() : Set.copyOf(gatilhosAtivos);
        produtosContratados = produtosContratados == null ? Set.of() : Set.copyOf(produtosContratados);
        elegibilidades = elegibilidades == null ? Set.of() : Set.copyOf(elegibilidades);
        situacaoDaConta = situacaoDaConta == null ? SituacaoDaConta.DESCONHECIDA : situacaoDaConta;
    }

    public boolean jaContratou(String produto) {
        return produto != null && !produto.isBlank() && produtosContratados.contains(produto);
    }

    public boolean elegivelPara(String produto) {
        return produto != null && !produto.isBlank() && elegibilidades.contains(produto);
    }

    public boolean temAlgumGatilho(List<String> gatilhos) {
        return gatilhos.stream().anyMatch(gatilhosAtivos::contains);
    }
}
