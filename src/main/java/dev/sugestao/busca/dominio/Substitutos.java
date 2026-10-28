package dev.sugestao.busca.dominio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A tabela de linhas substitutas, uma por jornada.
 *
 * <p><strong>É um valor em memória e deliberadamente não é uma porta.</strong> A
 * tentação de fazer disso um {@code FonteDeSubstitutos} com adaptador de Solr é
 * real — o conteúdo é de catálogo, e catálogo mora no índice. Mas esta tabela é
 * consultada exatamente no momento em que a lista veio vazia, que é dentro do
 * orçamento de 30 ms, depois de o índice já ter sido consultado uma ou duas
 * vezes. Uma porta aqui seria um convite permanente a transformar o caminho de
 * recuperação numa terceira ida à rede, no pior instante possível — e pior: a
 * recuperação passaria a falhar justamente quando o Solr está ruim, que é quando
 * mais buscas voltam vazias.
 *
 * <p>Então a decisão é anterior ao código: <em>o conteúdo da substituta é
 * carregado na subida e não sai da memória.</em> Hoje ele vem da configuração.
 * Em produção viria do catálogo, lido uma vez na inicialização e recarregado por
 * um gatilho fora do caminho quente — e o tipo que o domínio recebe continuaria
 * sendo este, porque o que o domínio precisa saber é "qual a linha da jornada X",
 * não de onde ela veio.
 *
 * <p>Jornada sem substituta declarada devolve vazio, e a lista continua vazia.
 * É falha fechada: oferecer a linha errada é pior que não oferecer nenhuma, e
 * declarar substituta é decisão de quem conhece a jornada.
 */
public final class Substitutos {

    /** Nenhuma substituta declarada. O comportamento anterior ao recurso. */
    public static final Substitutos NENHUM = new Substitutos(List.of());

    private final Map<String, Substituto> porJornada;

    public Substitutos(List<Substituto> declaradas) {
        var mapa = new LinkedHashMap<String, Substituto>();

        for (Substituto substituta : declaradas == null ? List.<Substituto>of() : declaradas) {
            // Duplicata é erro de configuração, não última-vence. Duas linhas
            // para a mesma jornada num arquivo de propriedades são sempre um
            // descuido de edição, e "a de baixo ganha" é a regra que faz esse
            // descuido virar comportamento silencioso.
            Substituto anterior = mapa.putIfAbsent(substituta.chave(), substituta);
            if (anterior != null) {
                throw new IllegalArgumentException(
                        "duas substitutas declaradas para a jornada '" + substituta.jornada()
                                + "': '" + anterior.termo() + "' e '" + substituta.termo() + "'");
            }
        }
        this.porJornada = Map.copyOf(mapa);
    }

    public boolean vazio() {
        return porJornada.isEmpty();
    }

    public int tamanho() {
        return porJornada.size();
    }

    /** A linha da jornada, se alguém a declarou. */
    public Optional<Candidato> para(String jornada) {
        if (Jornada.ausente(jornada)) {
            return Optional.empty();
        }
        return Optional.ofNullable(porJornada.get(Normalizacao.aplicar(jornada)))
                .map(Substituto::paraCandidato);
    }
}
