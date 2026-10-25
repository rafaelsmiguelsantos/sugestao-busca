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
 * <p><strong>4. Fadiga — e aqui uma invariante caiu.</strong> Até esta rodada,
 * todos os sinais só promoviam, e o comentário nesta classe dizia que rebaixar
 * sem dizer por quê é como remover sem registrar. A frase continua certa; o que
 * mudou é que havia um defeito maior do outro lado. Sem sinal negativo, o
 * ranking só sabe concordar consigo mesmo: uma linha exibida quarenta vezes e
 * nunca clicada não perdia nada, porque {@code ctr} mede a <em>população</em> e
 * não esta pessoa.
 *
 * <p>A invariante foi substituída por uma mais fraca e verificável: <em>rebaixar
 * é permitido, apagar não, e todo rebaixamento vai para o rastro</em>. A fadiga
 * tem piso ({@code fadigaMinima}), nunca zera a linha, e aparece linha por linha
 * na explicação — para remover continua existindo a {@link Peneira}, que devolve
 * motivo.
 *
 * <p><strong>A ordem das operações não é intercambiável:</strong> promover,
 * depois limitar no teto, depois rebaixar pela fadiga. Limitar no fim deixaria a
 * fadiga ser diluída por promoções que o teto ia cortar de qualquer forma;
 * rebaixar antes do teto faria a fadiga desaparecer quando o produto das
 * promoções estourasse o limite — ou seja, a fadiga pararia de funcionar
 * exatamente para quem tem mais afinidade, que é quem mais precisa dela.
 *
 * <p>O risco conhecido desta classe é a bolha: afinidade reforça afinidade, e
 * um cliente que clicou três vezes em crédito passa a ver crédito sempre. Três
 * coisas seguram isso — o teto declarado em {@code fatorMaximo}, pelo qual todos
 * os sinais competem; a cota do {@link Misturador}, que garante que a lista
 * continue mostrando caminhos que o ranking sozinho teria enterrado; e agora a
 * fadiga, que é o primeiro mecanismo do projeto capaz de desfazer uma preferência
 * em vez de só deixá-la decair.
 */
public final class Rankeador {

    /*
     * O sentinela de "sem jornada" mora em Jornada.AUSENTE. Deixa-la concorrer
     * faria dela a lider quase sempre, so por ser a mais numerosa — e o efeito
     * seria inclinar a lista para "mais do mesmo texto" em vez de para uma
     * intencao.
     */

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

    /**
     * O fator de um candidato: promoções, teto, fadiga.
     *
     * <p>Exposto para o rastro poder mostrar o número que moveu a linha. Ver
     * {@link #fadigaDe}, que o complementa.
     */
    public double fator(Candidato candidato, Perfil perfil, String lider, PesosDeRanking pesos) {
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

        // O nível do produto. Entra multiplicando o da jornada, e não
        // substituindo: querer crédito e querer consignado são duas afirmações,
        // e a segunda não anula a primeira.
        double afinidadeDoProduto = perfil.afinidadeComProduto(candidato.produtoAlvo());
        if (afinidadeDoProduto > 0) {
            fator *= 1.0 + afinidadeDoProduto * (pesos.afinidadeDeProdutoMaxima() - 1.0);
        }

        // A tendência só promove, nunca rebaixa, e isso é assimétrico de
        // propósito. Ela vive em torno de 1,0 e pode vir abaixo — interesse
        // esfriando —, mas rebaixar por tendência fria seria punir quem
        // demonstrou interesse há três semanas, e o decaimento da afinidade já
        // cuida do esquecimento com uma curva que tem física por trás. Aqui só o
        // lado de cima é usado.
        double tendencia = perfil.tendenciaCom(candidato.jornada());
        if (tendencia > 1.0) {
            double excedente = Math.min(1.0, tendencia - 1.0);
            fator *= 1.0 + excedente * (pesos.tendenciaMaxima() - 1.0);
        }

        // Teto ANTES da fadiga. Ver a nota de ordem no cabeçalho da classe.
        fator = Math.min(pesos.fatorMaximo(), fator);

        return fator * fadigaDe(candidato, perfil, pesos);
    }

    /**
     * O multiplicador da fadiga: 1,0 sem fadiga, {@code fadigaMinima} no teto.
     *
     * <p>Separado para o rastro poder registrar o rebaixamento à parte da
     * promoção. Sem isso, a explicação mostraria um fator só e ninguém saberia se
     * a linha está baixa por não ter sido promovida ou por ter sido rebaixada —
     * que são diagnósticos opostos.
     */
    public double fadigaDe(Candidato candidato, Perfil perfil, PesosDeRanking pesos) {
        double fadiga = perfil.fadigaDe(candidato.termo());
        if (fadiga <= 0) {
            return 1.0;
        }
        return 1.0 - fadiga * (1.0 - pesos.fadigaMinima());
    }

    /**
     * A jornada com mais força entre os candidatos do índice — desde que ela
     * supere a segunda colocada pela margem. Empate não elege ninguém.
     */
    private String jornadaLider(List<Candidato> candidatos, double margem) {
        Map<String, Double> forca = new HashMap<>();

        for (Candidato candidato : candidatos) {
            if (!candidato.procedencia().temScoreDoIndice()) {
                continue;
            }
            if (Jornada.ausente(candidato.jornada())) {
                continue;
            }
            forca.merge(candidato.jornada(), candidato.relevancia(), Double::sum);
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
                .filter(c -> c.procedencia().temScoreDoIndice())
                .mapToDouble(Candidato::relevancia)
                .max()
                .orElse(0.0);

        if (maior <= 0.0) {
            return candidatos;
        }
        return candidatos.stream()
                .map(c -> c.procedencia().temScoreDoIndice() ? c.com(c.relevancia() / maior) : c)
                .toList();
    }

    /** Exposto para teste e para o rastro: a ordem que o ranking produziu. */
    public static Comparator<Candidato> maisRelevantePrimeiro() {
        return Comparator.comparingDouble(Candidato::relevancia).reversed()
                .thenComparing(c -> c.origem().prioridade());
    }
}
