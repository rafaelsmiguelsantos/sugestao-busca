package dev.sugestao.busca.dominio;

/**
 * A jornada de produto — e, sobretudo, o que significa <em>não</em> ter uma.
 *
 * <p>Existe por um motivo só: o sentinela {@code "busca"} estava escrito à mão
 * em três lugares que precisam concordar. O {@link Rankeador} o excluía da
 * eleição de jornada líder, {@code Oferta} o recusava no cadastro, e
 * {@link Candidato#completarCom} não o conhecia — e foi aí que a conta não
 * fechou. Um termo do histórico do cliente chega com {@code jornada = "busca"},
 * que não é vazio; na deduplicação contra o item de catálogo ele herdava
 * identificador e produto da oferta e <strong>mantinha</strong> a jornada
 * sentinela. O resultado era a pior combinação possível: uma oferta
 * identificada, acionável, que o ranking tratava como se não pertencesse a
 * jornada nenhuma — justamente a linha com o sinal mais forte do conjunto, a
 * que a própria pessoa já procurou.
 *
 * <p>Então {@code "busca"} não é uma jornada chamada busca. É a ausência de
 * jornada, escrita como texto porque o índice guarda texto, e tratá-la como
 * valor em vez de como ausência é o erro que esta classe impede de repetir.
 */
public final class Jornada {

    /**
     * O balde dos termos sem jornada: o que veio do log de busca e o histórico
     * do próprio cliente. Não é nome de jornada de produto e nunca lidera
     * ranking.
     */
    public static final String AUSENTE = "busca";

    private Jornada() {
    }

    /**
     * Se este texto representa ausência de jornada — nulo, vazio, ou o
     * sentinela.
     *
     * <p>Os três são a mesma coisa para quem pergunta "a qual jornada esta
     * linha pertence?", e separá-los só multiplica os lugares onde um deles é
     * esquecido.
     */
    public static boolean ausente(String jornada) {
        return jornada == null || jornada.isBlank() || AUSENTE.equals(jornada.strip());
    }
}
