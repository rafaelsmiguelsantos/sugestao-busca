package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.aplicacao.ToleranciaAplicada;
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

    Busca candidatos(Prefixo prefixo, ContextoDeBusca contexto, int limite);

    /**
     * Os candidatos e <em>como</em> eles foram obtidos.
     *
     * <p>A tolerância a erro de digitação vive inteira dentro do adaptador, e
     * isso é deliberado: quantas idas ao índice uma consulta custa é detalhe de
     * implementação do adaptador, não decisão da aplicação. Mas o
     * <em>resultado</em> dessa decisão tem de atravessar a porta, porque é
     * métrica de qualidade e porque é o único jeito de saber se a tolerância
     * está recuperando busca ou inventando resposta.
     *
     * <p>Antes disto a porta devolvia {@code List<Candidato>} puro. Trocar por um
     * tipo só para carregar um enum pareceu exagero por um minuto, e não é: sem o
     * enum, a única evidência de que a segunda tentativa existiu seria a
     * latência — que é precisamente o sinal que ninguém olha quando a resposta
     * veio preenchida.
     */
    record Busca(List<Candidato> candidatos, ToleranciaAplicada tolerancia) {

        public Busca {
            candidatos = candidatos == null ? List.of() : List.copyOf(candidatos);
            tolerancia = tolerancia == null ? ToleranciaAplicada.NAO_PRECISOU : tolerancia;
        }

        public static Busca vazia() {
            return new Busca(List.of(), ToleranciaAplicada.NAO_PRECISOU);
        }

        public static Busca exata(List<Candidato> candidatos) {
            return new Busca(candidatos, ToleranciaAplicada.NAO_PRECISOU);
        }
    }
}
