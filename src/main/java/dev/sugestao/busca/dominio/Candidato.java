package dev.sugestao.busca.dominio;

import java.util.List;
import java.util.Objects;

/**
 * Uma linha em potencial, antes da peneira e da mistura. Carrega o que o perfil
 * precisa para julgá-la: o produto que ela empurra, se ela exige elegibilidade e
 * quais gatilhos a tornam oportuna.
 *
 * <p>{@code clientesDistintos} viaja junto de propósito. O piso de k-anonimato é
 * um {@code fq} no índice — este campo é só a rede de segurança do BFF, para o
 * caso de uma coleção ter sido publicada com o invariante violado.
 */
public record Candidato(
        String termo,
        String rotulo,
        String destino,
        String jornada,
        String produtoAlvo,
        boolean exigeElegibilidade,
        List<String> gatilhos,
        int clientesDistintos,
        double relevancia,
        Origem origem) {

    public Candidato {
        Objects.requireNonNull(termo, "termo");
        gatilhos = gatilhos == null ? List.of() : List.copyOf(gatilhos);
        origem = origem == null ? Origem.POPULAR : origem;
    }

    /** A chave de deduplicação. Duas redações da mesma intenção colidem aqui. */
    public String chave() {
        return Normalizacao.aplicar(termo);
    }

    public Candidato com(Origem outra) {
        return new Candidato(termo, rotulo, destino, jornada, produtoAlvo,
                exigeElegibilidade, gatilhos, clientesDistintos, relevancia, outra);
    }

    public Candidato com(double outraRelevancia) {
        return new Candidato(termo, rotulo, destino, jornada, produtoAlvo,
                exigeElegibilidade, gatilhos, clientesDistintos, outraRelevancia, origem);
    }

    /**
     * Herda do candidato descartado o que o vencedor não tem. Quando o termo
     * vem do histórico e também do catálogo, o histórico ganha a linha — mas
     * perderia o destino real e voltaria a cair numa busca por texto. Aqui ele
     * fica com a origem dele e com o caminho do catálogo.
     */
    public Candidato completarCom(Candidato outro) {
        return new Candidato(
                termo,
                vazio(rotulo) ? outro.rotulo() : rotulo,
                vazio(destino) ? outro.destino() : destino,
                vazio(jornada) ? outro.jornada() : jornada,
                vazio(produtoAlvo) ? outro.produtoAlvo() : produtoAlvo,
                exigeElegibilidade,
                gatilhos.isEmpty() ? outro.gatilhos() : gatilhos,
                Math.max(clientesDistintos, outro.clientesDistintos()),
                Math.max(relevancia, outro.relevancia()),
                origem);
    }

    /**
     * Sob que nome a elegibilidade deste candidato é concedida.
     *
     * <p>Quase sempre é o produto. Mas há item de catálogo que exige
     * elegibilidade e não empurra produto nenhum — portabilidade de salário é um
     * deles. Sem este desvio, "exige elegibilidade" com produto vazio nunca
     * encontraria concessão e o item sumiria da lista para todo mundo, para
     * sempre, sem ninguém perceber: a falha fechada estaria certa na direção e
     * errada no alvo.
     */
    public String chaveDeElegibilidade() {
        return vazio(produtoAlvo) ? chave() : produtoAlvo;
    }

    private static boolean vazio(String texto) {
        return texto == null || texto.isBlank();
    }

    public Sugestao paraSugestao() {
        return new Sugestao(termo, rotulo, destino, origem);
    }
}
