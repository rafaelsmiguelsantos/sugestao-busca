package dev.sugestao.busca.dominio;

/**
 * Oito posições, com teto por origem. É a diferença entre uma lista que mostra
 * quatro caminhos e uma que repete o mais popular quatro vezes.
 *
 * <p>O evento tem cota de uma linha e só a primeira: ele é oportuno, não
 * dominante. Se ele disputasse por score, um gatilho forte empurraria a lista
 * inteira para uma intenção que o cliente talvez nem estivesse perseguindo.
 */
public record Cotas(int historico, int evento, int limite) {

    public Cotas {
        if (limite <= 0) {
            throw new IllegalArgumentException("limite da lista precisa ser positivo");
        }
    }

    public static Cotas padrao() {
        return new Cotas(2, 1, 8);
    }
}
