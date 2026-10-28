package dev.sugestao.busca.dominio;

import java.util.List;

/**
 * A linha que uma jornada oferece quando a oferta dela foi removida pela peneira.
 *
 * <p>É o caminho da jornada que <strong>não exige nada</strong>: simular, ler
 * como funciona, falar com alguém. Alguém que digitou "veículos" e tem a oferta
 * de financiamento vetada por elegibilidade não deixou de estar interessado em
 * financiamento de veículo — deixou de poder contratá-lo hoje. A lista vazia
 * trata as duas coisas como a mesma.
 *
 * <p><strong>Por que ela não pode ter restrição, nem produto alvo.</strong> Os
 * dois são verificados no construtor, e não na {@link Peneira}, de propósito. Uma
 * substituta com restrição poderia ser removida pela mesma peneira que acabou de
 * esvaziar a lista — e aí o recurso teria um caminho em que ele não faz nada e
 * ninguém descobre, porque o sintoma é idêntico ao de antes dele existir. Com a
 * exigência no construtor, a configuração inválida derruba a subida, que é onde
 * esse erro é barato.
 *
 * <p>Pela mesma razão ela nunca é oferta: sem {@code ofertaId} e sem
 * {@code produtoAlvo}, {@link Candidato#ehOferta()} é falso, e
 * {@link ListaDeSugestoes#melhorOferta()} continua vazio. Anunciar como oferta
 * acionável uma linha de ajuda daria à jornada consumidora um identificador que
 * ela não tem como acionar.
 *
 * <p>A relevância nasce zero e isso é declaração, não descuido: a substituta não
 * foi pontuada contra nada — ela entra depois do ranking, quando não sobrou
 * ninguém com quem comparar. Atribuir-lhe um score a tornaria comparável com
 * linhas que de fato disputaram posição.
 */
public record Substituto(String jornada, String termo, String rotulo, String destino) {

    public Substituto {
        jornada = texto(jornada);
        termo = texto(termo);
        rotulo = texto(rotulo);
        destino = texto(destino);

        if (Jornada.ausente(jornada)) {
            throw new IllegalArgumentException(
                    "substituta sem jornada, ou na jornada sentinela '" + Jornada.AUSENTE
                            + "': ela e escolhida PELA jornada do que foi removido, "
                            + "e sem jornada nunca seria alcancada");
        }
        if (termo.isEmpty()) {
            throw new IllegalArgumentException("substituta de '" + jornada + "' sem termo");
        }
        if (!destino.startsWith("/") || destino.startsWith("//")) {
            // Mesma regra do cadastro de oferta, e pelo mesmo motivo: o destino é
            // devolvido ao app e o app navega para ele. Aceitar esquema aqui faria
            // de um arquivo de configuração um redirecionador aberto.
            throw new IllegalArgumentException(
                    "destino da substituta de '" + jornada
                            + "' precisa ser caminho relativo comecando com '/', sem esquema e sem '//'");
        }
    }

    /** A chave de busca: a jornada, insensível a caixa e a espaço. */
    public String chave() {
        return Normalizacao.aplicar(jornada);
    }

    /**
     * A linha pronta para entrar na lista.
     *
     * <p>Zero clientes distintos e procedência de catálogo: não há comportamento
     * de ninguém aqui, há uma decisão de produto. Ver {@link Procedencia}.
     */
    public Candidato paraCandidato() {
        return new Candidato(
                "",
                termo,
                rotulo,
                destino,
                jornada,
                "",
                Restricoes.NENHUMA,
                List.of(),
                0,
                0.0,
                Origem.SUBSTITUTO,
                Procedencia.CATALOGO);
    }

    private static String texto(String valor) {
        return valor == null ? "" : valor.strip();
    }
}
