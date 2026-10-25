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

    /**
     * As mesmas cotas com outro teto de lista, para o {@code ?limite=} da
     * requisição.
     *
     * <p>As cotas de origem não encolhem junto, e é isso que dá ao
     * {@code limite} a única propriedade que importa para quem integra:
     * <strong>pedir N devolve exatamente os N primeiros de pedir o máximo.</strong>
     * Uma jornada que pede uma linha recebe a mesma linha que receberia na
     * posição 0 pedindo oito.
     *
     * <p>A propriedade vem do {@link Misturador}, que monta a lista numa ordem
     * fixa — evento primeiro, depois o resto por relevância com a cota de
     * histórico sendo gasta na mesma ordem — e só para mais cedo quando o teto
     * chega. O teto corta o fim; ele nunca reordena o começo.
     *
     * <p>Encolher as cotas junto quebraria exatamente isso. Com cota de evento
     * proporcional, {@code limite=1} zeraria a vaga do evento e devolveria o
     * primeiro candidato por relevância — que é outra linha, não a mesma lista
     * mais curta. O chamador perderia a capacidade de pedir pouco sem mudar a
     * resposta.
     */
    public Cotas comLimite(int outroLimite) {
        return new Cotas(historico, evento, outroLimite);
    }
}
