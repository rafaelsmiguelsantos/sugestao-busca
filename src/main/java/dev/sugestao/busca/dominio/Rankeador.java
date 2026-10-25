package dev.sugestao.busca.dominio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decide a ordem. Três camadas de sinal, aplicadas em cima do score do índice.
 *
 * <p><strong>1. Escala.</strong> O score do Solr e o score do histórico não
 * nascem comparáveis. Sem normalizar, qual deles ganha é acidente de magnitude.
 *
 * <p><strong>2. Jornada.</strong> O índice devolve candidatos de várias
 * jornadas misturados. Quando uma delas domina o conjunto, ela é a intenção que
 * o prefixo revela — e aí o <em>produto</em> dessa jornada lidera, não o artigo
 * de ajuda sobre ela. Quem digita "emprestim" quer contratar, não entender: o
 * artigo continua na lista, uma linha abaixo.
 *
 * <p><strong>3. Comportamento do cliente.</strong> Distinto do {@code ctr} e da
 * {@code taxa_adesao} que já estão no {@code boost} do Solr — aqueles são
 * agregados da população, e dizem o que <em>as pessoas</em> fazem. Este diz o
 * que <em>esta</em> pessoa faz, e vem do perfil que já está sendo lido em
 * paralelo. Nenhuma ida extra a dependência: no orçamento de 30 ms não cabe.
 *
 * <p>Os três sinais só promovem, nunca rebaixam. Rebaixar sem dizer por quê é
 * como remover sem registrar — para remover existe a {@link Peneira}, que
 * devolve o motivo.
 *
 * <p>O risco conhecido desta classe é a bolha: afinidade reforça afinidade, e
 * um cliente que clicou três vezes em crédito passa a ver crédito sempre. O
 * teto em {@code afinidadeMaxima} e a cota do {@link Misturador} são o que
 * seguram isso — a cota garante que a lista continue mostrando caminhos que o
 * ranking sozinho teria enterrado.
 */
public final class Rankeador {

    /**
     * {@code busca} não é jornada, é a ausência de uma: é o balde dos termos
     * vindos do log e do histórico. Deixá-la concorrer faria dela a líder quase
     * sempre, só por ser a mais numerosa — e o efeito seria inclinar a lista
     * para "mais do mesmo texto" em vez de para uma intenção.
     */
    private static final String SEM_JORNADA = "busca";

    public Ranqueamento ranquear(List<Candidato> candidatos, Perfil perfil, PesosDeRanking pesos) {
        if (candidatos.isEmpty()) {
            return new Ranqueamento(List.of(), "");
        }

        List<Candidato> naEscala = normalizar(candidatos);
        String lider = jornadaLider(naEscala, pesos.margemParaLiderar());

        var ajustados = new ArrayList<Candidato>(naEscala.size());
        for (Candidato candidato : naEscala) {
            ajustados.add(candidato.com(candidato.relevancia() * fator(candidato, perfil, lider, pesos)));
        }
        return new Ranqueamento(ajustados, lider);
    }

    private double fator(Candidato candidato, Perfil perfil, String lider, PesosDeRanking pesos) {
        double fator = 1.0;

        if (!lider.isBlank() && lider.equals(candidato.jornada())) {
            fator *= pesos.jornadaLider();

            // Dentro da jornada que lidera, quem empurra produto vem primeiro.
            // É a diferença entre responder "o que você quer fazer" e responder
            // "sobre o que você quer ler".
            if (!candidato.produtoAlvo().isBlank()) {
                fator *= pesos.produtoDaJornada();
            }
        }

        double afinidade = perfil.afinidadeCom(candidato.jornada());
        if (afinidade > 0) {
            fator *= 1.0 + afinidade * (pesos.afinidadeMaxima() - 1.0);
        }
        return fator;
    }

    /**
     * A jornada com mais força entre os candidatos do índice — desde que ela
     * supere a segunda colocada pela margem. Empate não elege ninguém.
     */
    private String jornadaLider(List<Candidato> candidatos, double margem) {
        Map<String, Double> forca = new HashMap<>();

        for (Candidato candidato : candidatos) {
            if (!candidato.origem().veioDoIndice()) {
                continue;
            }
            String jornada = candidato.jornada();
            if (jornada == null || jornada.isBlank() || SEM_JORNADA.equals(jornada)) {
                continue;
            }
            forca.merge(jornada, candidato.relevancia(), Double::sum);
        }

        if (forca.isEmpty()) {
            return "";
        }

        var ordenadas = forca.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        double primeira = ordenadas.getFirst().getValue();
        double segunda = ordenadas.size() > 1 ? ordenadas.get(1).getValue() : 0.0;

        // Margem relativa, não absoluta: 15% à frente significa o mesmo com
        // dois candidatos ou com vinte.
        if (primeira <= 0 || (primeira - segunda) / primeira < margem) {
            return "";
        }
        return ordenadas.getFirst().getKey();
    }

    /**
     * Só o que veio do índice é reescalado; o histórico já chega entre 0 e 1,
     * porque ele nasce no próprio serviço e não no motor de busca.
     */
    private List<Candidato> normalizar(List<Candidato> candidatos) {
        double maior = candidatos.stream()
                .filter(c -> c.origem().veioDoIndice())
                .mapToDouble(Candidato::relevancia)
                .max()
                .orElse(0.0);

        if (maior <= 0.0) {
            return candidatos;
        }
        return candidatos.stream()
                .map(c -> c.origem().veioDoIndice() ? c.com(c.relevancia() / maior) : c)
                .toList();
    }

    /** Exposto para teste e para o rastro: a ordem que o ranking produziu. */
    public static Comparator<Candidato> maisRelevantePrimeiro() {
        return Comparator.comparingDouble(Candidato::relevancia).reversed()
                .thenComparing(c -> c.origem().prioridade());
    }
}
