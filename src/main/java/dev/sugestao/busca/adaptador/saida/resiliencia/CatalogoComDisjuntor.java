package dev.sugestao.busca.adaptador.saida.resiliencia;

import dev.sugestao.busca.aplicacao.porta.saida.CatalogoDeSugestoes;
import dev.sugestao.busca.dominio.ContextoDeBusca;
import dev.sugestao.busca.dominio.Prefixo;

/**
 * O disjuntor na frente do catálogo, como decorador da porta.
 *
 * <p><strong>É aqui que a arquitetura hexagonal se paga.</strong> Este arquivo é
 * novo, {@code Disjuntor} é novo, e <em>nada mais mudou</em>: o domínio não sabe
 * que existe disjuntor, o caso de uso não sabe, e o {@code CatalogoSolr} não
 * sabe. Ele implementa a mesma porta que decora, e quem decide empilhar os dois
 * é a classe de configuração — o único lugar do projeto que já conhecia a
 * implementação de cada porta.
 *
 * <p>Vale a pena olhar as alternativas, porque as três são tentadoras e as três
 * acoplam algo.
 *
 * <p><strong>No caso de uso.</strong> Seria uma classe da camada de aplicação
 * passando a conhecer um modo de falha de rede. O caso de uso já tem a sua
 * resiliência — prazo por dependência e caminho alternativo declarado — e ela é
 * sobre a <em>requisição</em>. Disjuntor é sobre a <em>dependência</em>. Juntar
 * as duas faria a aplicação ter de ser alterada no dia em que a dependência
 * virar outra coisa.
 *
 * <p><strong>Dentro do {@code CatalogoSolr}.</strong> Funcionaria, e aí todo
 * adaptador que precisasse de disjuntor reimplementaria o seu. Pior: ficaria
 * impossível ligar e desligar sem mexer no código que fala com o Solr, que é o
 * código que menos se quer mexer.
 *
 * <p><strong>Com {@code @Aspect} ou proxy do Spring.</strong> Resolveria sem
 * escrever classe nenhuma, e deixaria o comportamento invisível: ninguém lendo
 * {@code CatalogoSolr} descobriria que existe um disjuntor, e ninguém lendo a
 * configuração descobriria a ordem em que as coisas acontecem. Um decorador
 * explícito custa um arquivo e é legível de ponta a ponta.
 *
 * <p><strong>Por que só a porta de leitura.</strong> As outras duas portas do
 * Solr não ganham disjuntor, e cada uma tem o seu motivo:
 * <ul>
 *   <li>{@code RegistroDeOfertas} (cadastro) é de volume baixo, síncrono, e tem
 *       uma jornada esperando resposta. Não há tempestade de chamadas para
 *       conter, e recusar sem tentar faria um cadastro falhar por causa de
 *       falhas de <em>outras</em> chamadas — pior que a doença.</li>
 *   <li>{@code PublicadorDeVocabulario} roda no agendador, sem ninguém
 *       esperando, a cada poucos segundos. Tentar e falhar não custa nada e o
 *       agendador já é o mecanismo de nova tentativa.</li>
 * </ul>
 * O volume de teclas digitadas é o que justifica o disjuntor, e ele só existe
 * num lugar.
 *
 * <p>O que a aplicação vê quando o disjuntor abre é uma exceção da porta, igual
 * à que ela já via quando o Solr respondia com erro. O caso de uso a converte em
 * {@code Colheita.FALHOU} e devolve lista degradada — comportamento que já
 * existia, testado desde antes deste arquivo.
 */
public class CatalogoComDisjuntor implements CatalogoDeSugestoes {

    private final CatalogoDeSugestoes delegado;
    private final Disjuntor disjuntor;

    public CatalogoComDisjuntor(CatalogoDeSugestoes delegado, Disjuntor disjuntor) {
        this.delegado = delegado;
        this.disjuntor = disjuntor;
    }

    /**
     * A tolerância a erro de digitação fica <strong>dentro</strong> do que o
     * disjuntor protege, e não ao lado.
     *
     * <p>Consequência que vale dizer em voz alta: quando o Solr está ruim, a
     * segunda consulta não é tentada — ela está dentro da mesma chamada que o
     * disjuntor recusa. É o comportamento certo. A tolerância existe para
     * recuperar busca que falhou por erro de digitação, não para dobrar a carga
     * sobre um índice que já está entregando erro.
     */
    @Override
    public Busca candidatos(Prefixo prefixo, ContextoDeBusca contexto, int limite) {
        return disjuntor.executar(() -> delegado.candidatos(prefixo, contexto, limite));
    }

    /** Para o teste e para quem precisar observar o estado. */
    public Disjuntor.Estado estado() {
        return disjuntor.estado();
    }
}
