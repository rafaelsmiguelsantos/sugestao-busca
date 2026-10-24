package dev.sugestao.busca.aplicacao.porta.entrada;

import java.util.Map;

/**
 * Quantos eventos entraram, quantos o portão barrou, e por quais motivos.
 *
 * <p><strong>O termo descartado não aparece aqui, e isso não é descuido.</strong>
 * Devolver "descartei <em>pagar boleto do joão da silva</em>" num corpo de
 * resposta, ou escrevê-lo num log de aplicação, recria em outro lugar
 * exatamente o vazamento que o portão acabou de impedir. O que sai é a contagem
 * por motivo: suficiente para operar e para alarmar, insuficiente para
 * reconstruir o que alguém digitou.
 */
public record ResultadoDaIngestao(int aceitos, int descartados, Map<String, Integer> motivos) {

    public ResultadoDaIngestao {
        motivos = Map.copyOf(motivos);
    }

    public int recebidos() {
        return aceitos + descartados;
    }
}
