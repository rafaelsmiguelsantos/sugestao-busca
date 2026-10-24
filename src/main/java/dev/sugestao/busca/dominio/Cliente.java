package dev.sugestao.busca.dominio;

import java.util.Objects;

/**
 * O que o token diz sobre quem está digitando: identidade, segmento e faixa de
 * relacionamento. Nada aqui depende de leitura.
 *
 * <p>É essa independência que sustenta o leque paralelo. Os três atributos já
 * estão em mãos quando a requisição chega, então a consulta ao índice pode
 * arrancar junto da leitura do perfil em vez de atrás dela. Tudo o que é
 * comportamento ou estado de conta mora no {@link Perfil}, que é lido à parte e
 * pode faltar.
 */
public record Cliente(String id, Segmento segmento, Relacionamento relacionamento) {

    public Cliente {
        Objects.requireNonNull(id, "id do cliente");
        Objects.requireNonNull(segmento, "segmento do cliente");
        relacionamento = relacionamento == null ? Relacionamento.VAREJO : relacionamento;
    }

    public Cliente(String id, Segmento segmento) {
        this(id, segmento, Relacionamento.VAREJO);
    }

    /** O recorte que pode ir ao índice: coorte, sem identidade. */
    public ContextoDeBusca contexto() {
        return new ContextoDeBusca(segmento, relacionamento);
    }
}
