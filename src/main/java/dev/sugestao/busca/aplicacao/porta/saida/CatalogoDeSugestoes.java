package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.dominio.Candidato;
import dev.sugestao.busca.dominio.Prefixo;
import dev.sugestao.busca.dominio.Segmento;

import java.util.List;

/**
 * O índice de sugestões, visto de dentro. Nada nesta assinatura menciona Solr:
 * a aplicação pede candidatos para um prefixo e um segmento, e quem traduz isso
 * em {@code fq}, {@code bq} e {@code boost} é o adaptador.
 *
 * <p>Só entra aqui o que é conhecido antes do perfil — por isso a assinatura tem
 * segmento e não cliente. É essa restrição que permite ao leque disparar as duas
 * leituras ao mesmo tempo.
 */
public interface CatalogoDeSugestoes {

    List<Candidato> candidatos(Prefixo prefixo, Segmento segmento, int limite);
}
