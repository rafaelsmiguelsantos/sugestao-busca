package dev.sugestao.busca.adaptador.saida.resiliencia;

/**
 * A chamada não foi feita: o disjuntor está aberto.
 *
 * <p>É uma exceção e não um retorno vazio, e a diferença é a mesma que
 * {@code FalhaNoIndice} já defendia: devolver lista vazia aqui faria a aplicação
 * perder a distinção entre "não achei nada" e "não consegui perguntar". Quem
 * decide que o cliente não vai ver erro nenhum é o caso de uso, que tem o
 * caminho alternativo declarado — e ele já trata qualquer falha da porta do mesmo
 * jeito, o que é exatamente o motivo de o disjuntor não precisar de nenhuma
 * mudança lá.
 *
 * <p>Não estende {@code FalhaNoIndice} de propósito. Aquela exceção mora no
 * pacote do Solr, e o disjuntor não conhece Solr: ele decora uma porta, não uma
 * tecnologia. Herdar dela acoplaria o mecanismo de resiliência ao adaptador que
 * ele por acaso protege hoje.
 */
public class DisjuntorAberto extends RuntimeException {

    public DisjuntorAberto(String nome, long faltamMs) {
        super("disjuntor '" + nome + "' aberto; proxima sonda em " + faltamMs + " ms");
    }
}
