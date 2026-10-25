package dev.sugestao.busca.dominio.agregado;

import dev.sugestao.busca.dominio.Normalizacao;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Descobre a que jornada pertence um termo vindo do log, usando o catálogo
 * curado como régua.
 *
 * <p><strong>Por que isto precisa existir.</strong> Termo publicado a partir de
 * busca nasce sem jornada — ninguém o curou, então ele cai no balde
 * {@code busca}. Enquanto o ranking era só o score do índice, isso não
 * incomodava. Passou a incomodar quando o ranking ganhou jornada e afinidade:
 * os dois sinais são chaveados por jornada, e um termo sem jornada é um termo
 * que nenhum dos dois alcança. Como a camada popular é a maior parte da lista,
 * a personalização ficaria decorativa — funcionando só nas linhas de catálogo.
 *
 * <p><strong>Como classifica.</strong> Cruza as palavras do termo com as
 * palavras dos itens curados, pesando por tamanho: palavra longa é mais
 * específica, e "consignado" diz muito mais sobre a intenção do que "de".
 * Verbo de busca não conta — "contratar", "simular" e "ver" aparecem em todas
 * as jornadas e não distinguem nenhuma.
 *
 * <p><strong>Na dúvida, não classifica.</strong> Exige uma margem sobre a
 * segunda colocada, pelo mesmo motivo do ranking: "transferir dinheiro" encosta
 * em crédito e em investimento, e escolher uma delas por diferença mínima é
 * inventar uma intenção. Sem margem, o termo fica em {@code busca} — que é
 * exatamente onde ele estava antes, então errar aqui não piora nada.
 */
public final class ClassificadorDeJornada {

    public static final String SEM_JORNADA = "busca";

    /** Quanto a melhor jornada precisa superar a segunda, em proporção. */
    private static final double MARGEM = 0.30;

    /** Palavra de uma letra não diz nada e aparece em tudo. */
    private static final int TAMANHO_MINIMO = 3;

    /**
     * Verbos de busca e palavras de ligação. Aparecem em toda jornada, então
     * contá-los só adiciona ruído proporcional ao tamanho do catálogo.
     */
    private static final Set<String> SEM_SINAL = Set.of(
            "contratar", "simular", "consultar", "ver", "pagar", "fazer", "quero",
            "como", "qual", "onde", "para", "por", "com", "sem", "meu", "minha",
            "que", "dos", "das", "uma", "the", "novo", "nova", "mais");

    private final Map<String, Map<String, Double>> pesoPorPalavra;

    public ClassificadorDeJornada(List<ItemCurado> catalogo) {
        this.pesoPorPalavra = indexar(catalogo);
    }

    public String classificar(String termo) {
        var palavras = palavrasDe(termo);
        if (palavras.isEmpty()) {
            return SEM_JORNADA;
        }

        var forca = new HashMap<String, Double>();
        for (String palavra : palavras) {
            var jornadas = pesoPorPalavra.get(palavra);
            if (jornadas != null) {
                jornadas.forEach((jornada, peso) -> forca.merge(jornada, peso, Double::sum));
            }
        }
        if (forca.isEmpty()) {
            return SEM_JORNADA;
        }

        var ordenadas = forca.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        double primeira = ordenadas.getFirst().getValue();
        double segunda = ordenadas.size() > 1 ? ordenadas.get(1).getValue() : 0.0;

        if (primeira <= 0 || (primeira - segunda) / primeira < MARGEM) {
            return SEM_JORNADA;
        }
        return ordenadas.getFirst().getKey();
    }

    /**
     * Palavra → jornada → peso. O peso é o tamanho da palavra, e a mesma
     * palavra pode apontar para várias jornadas — é o empate entre elas que a
     * margem resolve depois.
     */
    private static Map<String, Map<String, Double>> indexar(List<ItemCurado> catalogo) {
        var indice = new HashMap<String, Map<String, Double>>();

        for (ItemCurado item : catalogo) {
            if (item.jornada() == null || item.jornada().isBlank()
                    || SEM_JORNADA.equals(item.jornada())) {
                continue;
            }
            var palavras = new HashSet<String>(palavrasDe(item.termo()));
            for (String sinonimo : item.sinonimos()) {
                palavras.addAll(palavrasDe(sinonimo));
            }
            for (String palavra : palavras) {
                indice.computeIfAbsent(palavra, p -> new HashMap<>())
                        .merge(item.jornada(), (double) palavra.length(), Double::sum);
            }
        }
        return Map.copyOf(indice);
    }

    private static List<String> palavrasDe(String texto) {
        if (texto == null || texto.isBlank()) {
            return List.of();
        }
        return Arrays.stream(Normalizacao.aplicar(texto).split(" "))
                .filter(p -> p.length() >= TAMANHO_MINIMO)
                .filter(p -> !SEM_SINAL.contains(p))
                .toList();
    }
}
