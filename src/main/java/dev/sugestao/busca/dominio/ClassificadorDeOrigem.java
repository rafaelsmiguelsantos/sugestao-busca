package dev.sugestao.busca.dominio;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Promove a EVENTO o candidato que o perfil torna oportuno agora.
 *
 * <p>O gatilho está no documento ({@code gatilho_ss}) e os gatilhos ativos estão
 * no perfil; o cruzamento é aqui, não no índice. Poderia ser um {@code bq} na
 * consulta, mas isso obrigaria a esperar o perfil antes de falar com o Solr — e
 * seria redundante, porque quem garante a linha do evento é a cota, não o score.
 *
 * <p>Promove no máximo quantos couberem na cota, e escolhe os mais relevantes.
 * Promover todos os que casam seria pior do que não promover nenhum: a cota
 * aceitaria um e <em>descartaria</em> o resto, e um item de catálogo perderia a
 * linha que teria ganhado sem gatilho nenhum. Gatilho só pode ajudar — se ele
 * pode tirar um item da lista, ele deixou de ser oportunidade e virou sorteio.
 */
public final class ClassificadorDeOrigem {

    public List<Candidato> classificar(List<Candidato> candidatos, Perfil perfil, int maximoDeEventos) {
        if (maximoDeEventos <= 0) {
            return candidatos;
        }

        Set<String> promovidos = candidatos.stream()
                .filter(c -> ehOportuno(c, perfil))
                .sorted(Comparator.comparingDouble(Candidato::relevancia).reversed())
                .limit(maximoDeEventos)
                .map(Candidato::chave)
                .collect(Collectors.toUnmodifiableSet());

        return candidatos.stream()
                .map(c -> promovidos.contains(c.chave()) ? c.com(Origem.EVENTO) : c)
                .toList();
    }

    /**
     * Só promove o que ainda não é evento e veio do índice. A segunda condição
     * exclui o histórico: ele não é oferta, é lembrança, e transformá-lo em
     * evento daria a uma busca antiga do próprio cliente a linha reservada à
     * oportunidade de agora.
     */
    private boolean ehOportuno(Candidato candidato, Perfil perfil) {
        return candidato.origem() != Origem.EVENTO
                && candidato.origem().veioDoIndice()
                && perfil.temAlgumGatilho(candidato.gatilhos());
    }
}
