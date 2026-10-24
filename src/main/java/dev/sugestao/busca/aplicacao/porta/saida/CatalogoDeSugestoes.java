package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.dominio.Candidato;
import dev.sugestao.busca.dominio.ContextoDeBusca;
import dev.sugestao.busca.dominio.Prefixo;

import java.util.List;

/**
 * O índice de sugestões, visto de dentro. Nada nesta assinatura menciona Solr:
 * a aplicação pede candidatos para um prefixo e uma coorte, e quem traduz isso
 * em {@code fq}, {@code bq} e {@code boost} é o adaptador.
 *
 * <p>Recebe {@link ContextoDeBusca} e não {@code Cliente} por decisão, não por
 * economia: o índice não pode receber identidade. Tudo o que entra aqui é
 * atributo de coorte, conhecido antes do perfil — e é essa restrição que permite
 * ao leque disparar as duas leituras ao mesmo tempo.
 */
public interface CatalogoDeSugestoes {

    List<Candidato> candidatos(Prefixo prefixo, ContextoDeBusca contexto, int limite);
}
