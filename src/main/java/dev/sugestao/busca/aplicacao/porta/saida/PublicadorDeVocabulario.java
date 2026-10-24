package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.dominio.agregado.TermoAgregado;

import java.util.List;

/**
 * Escreve no índice de sugestões.
 *
 * <p>É a única porta de escrita do vocabulário, e recebe apenas o que já passou
 * pelo piso de k-anonimato. A verificação não mora aqui de propósito: um
 * adaptador que também decide o que pode ser publicado é um adaptador que pode
 * ser trocado junto com a regra.
 */
public interface PublicadorDeVocabulario {

    /** Devolve quantos documentos entraram. */
    int publicar(List<TermoAgregado> termos);
}
