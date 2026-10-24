package dev.sugestao.busca.dominio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monta a lista final: deduplica, reserva a linha do evento, respeita a cota do
 * histórico e corta no limite.
 *
 * <p>A ordem importa. A deduplicação vem antes da cota — senão a mesma intenção
 * ocupa três linhas com três redações e ainda consome três vagas. E a cota vem
 * antes do corte, senão o limite seria preenchido pela origem mais popular antes
 * de qualquer outra ter chance.
 */
public final class Misturador {

    public ListaDeSugestoes misturar(List<Candidato> candidatos, Cotas cotas, boolean degradada) {
        List<Candidato> unicos = deduplicar(normalizar(candidatos));

        List<Candidato> escolhidos = new ArrayList<>(cotas.limite());

        // O evento abre a lista. Cota de uma linha e só a primeira: ele é
        // oportuno, não dominante.
        unicos.stream()
                .filter(c -> c.origem() == Origem.EVENTO)
                .sorted(maisRelevantePrimeiro())
                .limit(Math.min(cotas.evento(), cotas.limite()))
                .forEach(escolhidos::add);

        int historicoUsado = 0;
        for (Candidato candidato : unicos.stream()
                .filter(c -> c.origem() != Origem.EVENTO)
                .sorted(maisRelevantePrimeiro())
                .toList()) {

            if (escolhidos.size() >= cotas.limite()) {
                break;
            }
            if (candidato.origem() == Origem.HISTORICO) {
                if (historicoUsado >= cotas.historico()) {
                    continue;
                }
                historicoUsado++;
            }
            escolhidos.add(candidato);
        }

        return new ListaDeSugestoes(escolhidos.stream().map(Candidato::paraSugestao).toList(), degradada);
    }

    /**
     * Score do Solr e score do histórico não nascem na mesma escala, e sem isto
     * a comparação entre eles seria acidente de magnitude. Só o que veio do
     * índice é reescalado; o histórico já chega entre 0 e 1.
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

    private List<Candidato> deduplicar(List<Candidato> candidatos) {
        Map<String, Candidato> porChave = new LinkedHashMap<>();
        for (Candidato candidato : candidatos) {
            porChave.merge(candidato.chave(), candidato, Misturador::resolverColisao);
        }
        return List.copyOf(porChave.values());
    }

    /** Vence a origem mais forte; empate se resolve pelo score. O perdedor ainda
     *  serve para completar o que falta ao vencedor. */
    private static Candidato resolverColisao(Candidato atual, Candidato novo) {
        boolean novoVence = novo.origem().prioridade() < atual.origem().prioridade()
                || (novo.origem() == atual.origem() && novo.relevancia() > atual.relevancia());

        return novoVence ? novo.completarCom(atual) : atual.completarCom(novo);
    }

    private Comparator<Candidato> maisRelevantePrimeiro() {
        return Comparator.comparingDouble(Candidato::relevancia).reversed()
                .thenComparing(c -> c.origem().prioridade());
    }
}
