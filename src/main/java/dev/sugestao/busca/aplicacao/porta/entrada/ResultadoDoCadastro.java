package dev.sugestao.busca.aplicacao.porta.entrada;

import dev.sugestao.busca.dominio.oferta.Oferta;

/**
 * O que o cadastro fez.
 *
 * <p>{@code criada} distingue 201 de 200 na borda, e a distinção importa para
 * quem integra: um {@code POST} que devolve 200 avisa que havia oferta com aquele
 * identificador e ela foi substituída. Se a jornada esperava criar, isso é um
 * identificador colidindo — provavelmente dois produtos disputando o mesmo
 * código — e é melhor descobrir pelo status do que por uma oferta que mudou de
 * texto sozinha.
 *
 * <p>Devolve a oferta gravada, não a recebida: é ela que tem o termo normalizado
 * e o identificador em maiúscula.
 */
public record ResultadoDoCadastro(Oferta oferta, boolean criada) {

    public String ofertaId() {
        return oferta.ofertaId();
    }
}
