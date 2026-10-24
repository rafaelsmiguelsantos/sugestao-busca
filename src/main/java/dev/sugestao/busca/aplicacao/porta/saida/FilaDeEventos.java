package dev.sugestao.busca.aplicacao.porta.saida;

import dev.sugestao.busca.dominio.evento.EventoDeBusca;

import java.util.List;

/**
 * O lugar do MSK no desenho: uma porta só, particionada por cliente.
 *
 * <p>A ingestão publica e a agregação consome. As duas pontas estão na mesma
 * interface porque, do ponto de vista desta aplicação, o transporte é um
 * detalhe — trocar o adaptador em memória por um produtor e um consumidor
 * Kafka não muda nenhuma das duas chamadas.
 *
 * <p>O que não é detalhe é a partição. Ela é por identificador de cliente para
 * que todos os eventos de uma pessoa cheguem na mesma ordem em que ocorreram:
 * sem isso, um clique pode ser agregado antes da impressão que o produziu, e o
 * CTR passa a ter numerador sem denominador.
 */
public interface FilaDeEventos {

    void publicar(List<EventoDeBusca> eventos);

    /** Retira até {@code maximo} eventos. Lista vazia quando não há nada. */
    List<EventoDeBusca> consumir(int maximo);

    int pendentes();
}
