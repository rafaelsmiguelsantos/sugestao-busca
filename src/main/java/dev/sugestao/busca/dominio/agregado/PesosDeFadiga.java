package dev.sugestao.busca.dominio.agregado;

import java.time.Duration;
import java.time.Instant;

/**
 * Quando uma linha exibida muitas vezes e nunca clicada passa a valer menos.
 *
 * <p>É o único sinal do projeto que <strong>rebaixa</strong>, e isso contraria
 * uma invariante que o {@code Rankeador} mantinha de propósito: "os sinais só
 * promovem, nunca rebaixam; para remover existe a Peneira, que diz por quê". A
 * invariante caiu por um motivo concreto — sem sinal negativo o ranking só sabe
 * concordar consigo mesmo. Uma oferta exibida quarenta vezes e nunca clicada não
 * perdia nada e ficava no topo para sempre, porque {@code ctr} é da
 * <em>população</em> e não desta pessoa.
 *
 * <p>O princípio por trás da invariante, porém, continua valendo: rebaixar sem
 * dizer por quê é como remover sem registrar. Por isso a fadiga aplicada aparece
 * no rastro, linha por linha, e sai na explicação.
 *
 * <h2>As três defesas, e por que cada uma precisa existir</h2>
 *
 * <p><strong>1. Piso de evidência ({@code impressoesMinimas}).</strong> Sem ele,
 * oferta nova morre antes de nascer: recebe impressão, ainda não tem clique, é
 * penalizada, perde posição e nunca chega a receber clique. É o mesmo laço que o
 * piso de confiança da afinidade já corrigiu uma vez, na direção oposta — lá o
 * problema era acreditar com um clique, aqui é descrer com uma impressão.
 *
 * <p><strong>2. Teto ({@code fadigaMaxima}).</strong> A fadiga nunca zera a
 * linha, só a desconta. Zerar seria remover, e remover é trabalho da Peneira,
 * que devolve motivo. Uma linha que o cliente ignora dez vezes pode ser
 * exatamente a que ele vai clicar na décima primeira, quando a situação dele
 * mudar.
 *
 * <p><strong>3. Janela.</strong> A fadiga esquece. Ignorar uma oferta em março
 * não deve pesar em outubro — a conta mudou, a necessidade mudou, e o único
 * registro disso é a data.
 *
 * <h2>O que esta classe deliberadamente NÃO corrige</h2>
 *
 * <p><strong>Viés de posição.</strong> Uma linha na posição 5 é vista menos que
 * uma na posição 0, e contar "exibiu e não clicou" trata as duas igual. O efeito
 * é conhecido e a direção é ruim: a linha que já estava embaixo acumula fadiga
 * mais rápido, e afunda mais. Corrigir isso direito exige modelo de propensão —
 * estimar a probabilidade de a linha ter sido <em>vista</em>, não só exibida — e
 * isso é trabalho de outra ordem de grandeza.
 *
 * <p>Fica declarado como limite, e não disfarçado. A alternativa — esperar o
 * modelo de propensão para ter sinal negativo nenhum — deixaria o defeito maior
 * no lugar para evitar o menor.
 *
 * <p><strong>E impressão não é observação</strong>, o que em typeahead é extremo:
 * cada tecla gera uma impressão, e a lista é substituída antes de a pessoa ler a
 * terceira linha. O piso de evidência é o que torna isso suportável — ele exige
 * repetição, e repetição ao longo de buscas diferentes é evidência melhor que
 * uma impressão isolada.
 */
public record PesosDeFadiga(

        /** Impressões sem clique antes de qualquer penalidade. */
        int impressoesMinimas,

        /** Impressões sem clique em que a fadiga chega a 1,0. */
        int impressoesParaOTeto,

        /** Depois disto, a fadiga é esquecida. */
        Duration janela) {

    public PesosDeFadiga {
        if (impressoesMinimas < 1) {
            throw new IllegalArgumentException(
                    "sem piso de evidencia, oferta nova e penalizada antes do primeiro clique");
        }
        if (impressoesParaOTeto <= impressoesMinimas) {
            throw new IllegalArgumentException(
                    "a fadiga precisa crescer entre o piso e o teto, senao ela e um degrau");
        }
        if (janela == null || janela.isZero() || janela.isNegative()) {
            throw new IllegalArgumentException("a janela da fadiga precisa ser positiva");
        }
    }

    /**
     * Cinco impressões sem clique para começar, vinte para o teto, trinta dias
     * de memória.
     *
     * <p>Cinco é o menor número que não é acidente: numa caixa de busca, uma
     * linha aparece e desaparece a cada tecla, e uma a quatro impressões podem
     * ser uma única sessão de digitação em que a pessoa nem leu a lista. Cinco
     * exige que a linha tenha sobrevivido a buscas diferentes.
     *
     * <p><strong>Não há um "quanto perder" aqui, e isso é a correção de um
     * defeito.</strong> Esta classe tinha um {@code fadigaMaxima} de 0,4 e o
     * ranking tinha um {@code fadigaMinima} de 0,6, e os dois se multiplicavam:
     * no teto, o fator saía {@code 1 − 0,4 × (1 − 0,6) = 0,84}. Uma perda de 16%,
     * que não é o que nenhum dos dois números dizia — e foi uma verificação de
     * ponta a ponta esperando 0,6 e encontrando 0,84 que mostrou isso.
     *
     * <p>Agora esta classe responde apenas <em>quanto da penalidade se aplica</em>
     * (0 a 1), e <em>qual é a penalidade</em> é uma decisão só, em
     * {@code PesosDeRanking.fadigaMinima}. Dois tetos para a mesma coisa é como
     * se produz um efeito que ninguém consegue prever lendo a configuração.
     */
    public static PesosDeFadiga padrao() {
        return new PesosDeFadiga(5, 20, Duration.ofDays(30));
    }

    /**
     * A fração da penalidade a aplicar, entre 0 e 1.
     *
     * <p>Não é "quanto a linha perde" — é "quanto do desconto configurado vale
     * neste caso". A profundidade do desconto é
     * {@code PesosDeRanking.fadigaMinima}.
     *
     * <p><strong>Um clique zera.</strong> Não reduz: zera. A pergunta que a
     * fadiga responde é "esta pessoa ignora esta linha?", e um clique responde
     * não — independentemente de quantas impressões vieram antes. Descontar
     * proporcionalmente faria uma linha clicada uma vez em cinquenta impressões
     * continuar penalizada, quando ela é justamente uma linha que funciona,
     * apenas raramente.
     *
     * <p>Entre o piso e o teto o crescimento é linear. Não há motivo para ser
     * outra coisa: a curva exponencial da afinidade existe porque lá o eixo é
     * tempo, e tempo tem meia-vida; aqui o eixo é contagem de impressões, e
     * contagem não tem física nenhuma por trás.
     */
    public double fadigaDe(int impressoes, int cliques) {
        if (cliques > 0 || impressoes <= impressoesMinimas) {
            return 0.0;
        }

        double faixa = (double) (impressoesParaOTeto - impressoesMinimas);
        double andado = Math.min(faixa, impressoes - impressoesMinimas);

        return andado / faixa;
    }

    public Instant corte(Instant agora) {
        return agora.minus(janela);
    }
}
