package dev.sugestao.busca.aplicacao.porta.entrada;

/**
 * O chamador está autenticado e tem o escopo de escrita, mas a oferta é de outra
 * jornada.
 *
 * <p>É o buraco que o projeto declarou por várias versões e esta classe fecha:
 * sem ela, qualquer serviço com permissão de cadastrar oferta podia cadastrar
 * oferta de <em>qualquer</em> jornada. A jornada de seguros podia publicar uma
 * oferta de crédito, com rótulo e destino escolhidos por ela, e aparecer na
 * busca como se fosse do time de crédito.
 *
 * <p>Separada de {@code OfertaInvalida} de propósito. Aquela diz "o que você
 * mandou está errado" e a correção é no corpo; esta diz "o que você mandou está
 * certo e não é seu", e a correção é no token — ou em não mandar. Devolver as
 * duas como 422 obrigaria quem integra a ler a mensagem para saber se o problema
 * é dele ou da permissão dele.
 *
 * <p>Vira {@code 403} na borda, não {@code 404}. Esconder a existência da
 * jornada seria defensável num recurso privado; aqui a jornada que o chamador
 * tentou usar é um valor que ele mesmo enviou, então negá-la não revela nada que
 * ele já não soubesse.
 */
public class JornadaNaoAutorizada extends RuntimeException {

    private final String jornadaPedida;
    private final String jornadaDoChamador;

    public JornadaNaoAutorizada(String jornadaPedida, String jornadaDoChamador) {
        super("cadastro em '" + jornadaPedida + "' negado para quem e dono de '"
                + jornadaDoChamador + "'");
        this.jornadaPedida = jornadaPedida;
        this.jornadaDoChamador = jornadaDoChamador;
    }

    public String jornadaPedida() {
        return jornadaPedida;
    }

    public String jornadaDoChamador() {
        return jornadaDoChamador;
    }
}
