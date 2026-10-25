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
 *
 * <p>Tudo o que fica de fora sai registrado em {@link Descarte}. Numa camada de
 * sugestão, a pergunta que chega do negócio quase nunca é por que uma linha
 * apareceu — é por que a outra não apareceu.
 *
 * <p>Esta classe <strong>não pontua</strong>. Ela recebe os candidatos já
 * ranqueados pelo {@link Rankeador} e só decide quem cabe. Misturar e pontuar
 * eram a mesma classe até a normalização de escala precisar conviver com o peso
 * de jornada e o de afinidade — três regras de ordenação dentro de quem também
 * aplica cota é onde se perde a conta de qual delas moveu a linha.
 */
public final class Misturador {

    public Mistura misturar(List<Candidato> candidatos, Cotas cotas) {
        var descartes = new ArrayList<Descarte>();
        List<Candidato> unicos = deduplicar(candidatos, descartes);

        var escolhidos = new ArrayList<Candidato>(cotas.limite());

        // O evento abre a lista. Cota de uma linha e só a primeira: ele é
        // oportuno, não dominante.
        var eventos = unicos.stream()
                .filter(c -> c.origem() == Origem.EVENTO)
                .sorted(maisRelevantePrimeiro())
                .toList();

        int vagasDeEvento = Math.min(cotas.evento(), cotas.limite());
        for (int i = 0; i < eventos.size(); i++) {
            if (i < vagasDeEvento) {
                escolhidos.add(eventos.get(i));
            } else {
                descartes.add(Descarte.de(eventos.get(i), MotivoDeDescarte.COTA_DE_EVENTO));
            }
        }

        int historicoUsado = 0;
        for (Candidato candidato : unicos.stream()
                .filter(c -> c.origem() != Origem.EVENTO)
                .sorted(maisRelevantePrimeiro())
                .toList()) {

            if (escolhidos.size() >= cotas.limite()) {
                descartes.add(Descarte.de(candidato, MotivoDeDescarte.ALEM_DO_LIMITE));
                continue;
            }
            if (candidato.origem() == Origem.HISTORICO && historicoUsado >= cotas.historico()) {
                descartes.add(Descarte.de(candidato, MotivoDeDescarte.COTA_DE_HISTORICO));
                continue;
            }
            if (candidato.origem() == Origem.HISTORICO) {
                historicoUsado++;
            }
            escolhidos.add(candidato);
        }

        return new Mistura(escolhidos, descartes);
    }

    private List<Candidato> deduplicar(List<Candidato> candidatos, List<Descarte> descartes) {
        Map<String, Candidato> porChave = new LinkedHashMap<>();

        for (Candidato candidato : candidatos) {
            Candidato atual = porChave.get(candidato.chave());
            if (atual == null) {
                porChave.put(candidato.chave(), candidato);
                continue;
            }
            boolean novoVence = venceu(candidato, atual);
            Candidato ganhador = novoVence ? candidato : atual;
            Candidato perdedor = novoVence ? atual : candidato;

            porChave.put(candidato.chave(), ganhador.completarCom(perdedor));
            descartes.add(Descarte.de(perdedor, MotivoDeDescarte.TERMO_DUPLICADO));
        }
        return List.copyOf(porChave.values());
    }

    /** Vence a origem mais forte; empate se resolve pelo score. */
    private static boolean venceu(Candidato novo, Candidato atual) {
        return novo.origem().prioridade() < atual.origem().prioridade()
                || (novo.origem() == atual.origem() && novo.relevancia() > atual.relevancia());
    }

    private Comparator<Candidato> maisRelevantePrimeiro() {
        return Comparator.comparingDouble(Candidato::relevancia).reversed()
                .thenComparing(c -> c.origem().prioridade());
    }
}
