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

    /**
     * Quantos eventos a fila jogou fora por lotação. Acima de zero é alarme.
     *
     * <p>Está na porta porque <strong>descarte silencioso é o pior modo de falha
     * do caminho de escrita</strong>: a ingestão responde {@code 202}, o app
     * acredita que o evento entrou, e o aprendizado nunca o vê. Nada no sistema
     * fica diferente, e o sintoma é a personalização simplesmente piorar com o
     * aumento de tráfego — o momento em que ninguém está procurando um contador.
     *
     * <p>O teto existe para o POC não consumir a memória da máquina se o
     * agendador parar. Ele é a decisão certa; não contar quanto ele descartou
     * não era. Mesma lógica de {@code retidosPeloPiso} e do teto da fadiga:
     * limite que ninguém mede é limite que alguém afrouxa por palpite.
     *
     * <p>O padrão é zero para implementação que não tenha teto — uma fila de
     * verdade não descarta, ela aplica contrapressão.
     */
    default int descartadosPorLotacao() {
        return 0;
    }
}
