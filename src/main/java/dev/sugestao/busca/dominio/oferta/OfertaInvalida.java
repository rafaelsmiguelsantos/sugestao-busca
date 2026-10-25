package dev.sugestao.busca.dominio.oferta;

import java.util.List;

/**
 * O cadastro foi recusado, e aqui está tudo que estava errado — não a primeira
 * coisa que estava errada.
 *
 * <p>Acumular é decisão de projeto, não detalhe de implementação. Uma jornada
 * integrando contra esta API envia um corpo com quinze campos. Recusar no
 * primeiro problema transforma a integração em uma sequência de tentativas, uma
 * por campo errado, cada uma precisando de um ciclo de deploy do lado de quem
 * integra. Devolver as quinze violações de uma vez é a diferença entre uma
 * tarde e uma semana.
 *
 * <p>É uma exceção de domínio e não conhece HTTP. Quem traduz para
 * {@code 422} e para {@code application/problem+json} é a borda.
 */
public class OfertaInvalida extends RuntimeException {

    private final List<Violacao> violacoes;

    public OfertaInvalida(List<Violacao> violacoes) {
        super("oferta invalida: " + violacoes.size() + " violacao(oes)");
        this.violacoes = List.copyOf(violacoes);
    }

    public List<Violacao> violacoes() {
        return violacoes;
    }
}
