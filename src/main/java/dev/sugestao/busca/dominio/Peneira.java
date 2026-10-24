package dev.sugestao.busca.dominio;

import java.util.List;

/**
 * O que o perfil tira da lista depois que o índice já respondeu.
 *
 * <p>Por que aqui e não como {@code fq}: o segmento vem do token, então a
 * consulta ao Solr arranca junto com a leitura do perfil. Produto contratado e
 * elegibilidade só são conhecidos quando o perfil chega — transformá-los em
 * {@code fq} custaria enfileirar as duas chamadas, e o orçamento de 30 ms
 * passaria de folgado a apertado. É trabalho de CPU sobre vinte candidatos: 2 ms
 * no desenho, e não é onde o tempo mora.
 *
 * <p>O piso de k-anonimato é a exceção e continua sendo {@code fq} no índice.
 * O que está aqui é rede de segurança, não o controle: se um termo abaixo do
 * piso chegou até este ponto, a coleção foi publicada com o invariante violado
 * e isso é alarme, não ajuste de relevância.
 */
public final class Peneira {

    private final int pisoKAnonimato;

    public Peneira(int pisoKAnonimato) {
        this.pisoKAnonimato = pisoKAnonimato;
    }

    public List<Candidato> aplicar(List<Candidato> candidatos, Perfil perfil) {
        return candidatos.stream()
                .filter(c -> passaNoPiso(c))
                .filter(c -> !perfil.jaContratou(c.produtoAlvo()))
                .filter(c -> podeSerOferecido(c, perfil))
                .toList();
    }

    private boolean passaNoPiso(Candidato candidato) {
        return !candidato.origem().veioDoIndice()
                || candidato.clientesDistintos() >= pisoKAnonimato;
    }

    /** Falha fechada: sem elegibilidade conhecida, a oferta não aparece. */
    private boolean podeSerOferecido(Candidato candidato, Perfil perfil) {
        return !candidato.exigeElegibilidade()
                || perfil.elegivelPara(candidato.chaveDeElegibilidade());
    }
}
