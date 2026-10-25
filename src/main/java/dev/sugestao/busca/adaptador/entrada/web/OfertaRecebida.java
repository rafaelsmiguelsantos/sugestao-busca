package dev.sugestao.busca.adaptador.entrada.web;

import dev.sugestao.busca.aplicacao.porta.entrada.ComandoDeCadastroDeOferta;

import java.time.Instant;
import java.util.List;

/**
 * O corpo do {@code POST /v1/ofertas}.
 *
 * <p>Não valida nada e não tem anotação de validação, de propósito. A validação
 * inteira está em {@code Oferta.de}, no domínio, e duplicá-la aqui com
 * {@code @NotBlank} criaria duas listas de regras que divergem na primeira
 * mudança — com a diferença de que a desta camada só se descobre em produção,
 * porque é a que roda primeiro.
 *
 * <p><strong>As três restrições são {@link Boolean} e não {@code boolean}, e
 * isso não é descuido.</strong> Com primitivo, um campo ausente no JSON não
 * vira {@code false}: o Jackson recusa o corpo inteiro com
 * {@code Cannot map null into type boolean}, porque
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES} vem ligado no Spring Boot e um record não
 * tem valor inicial para ele cair. O resultado era {@code 400} com corpo vazio
 * para quem simplesmente não declarou uma restrição que não se aplica — o erro
 * menos acionável possível, numa chamada perfeitamente razoável.
 *
 * <p>Com {@code Boolean}, ausente é {@code null} e {@code null} é {@code false}
 * em {@link #paraComando()}. A direção do padrão importa: restrição ausente
 * significa oferta <em>sem</em> restrição, que é mais visível e não mais
 * permissiva — quem esconde oferta é a restrição, não a ausência dela. Assumir
 * {@code true} faria uma oferta sem restrições desaparecer para todo mundo por
 * causa de um campo esquecido, e desaparecer é o fracasso que ninguém reporta.
 */
public record OfertaRecebida(
        String ofertaId,
        String termo,
        List<String> sinonimos,
        String rotulo,
        String destino,
        String jornada,
        String produtoAlvo,
        Boolean exigeElegibilidade,
        Boolean vetaInadimplencia,
        Boolean exigeContaAtiva,
        List<String> gatilhos,
        List<String> segmentos,
        List<String> relacionamentos,
        Instant vigenciaInicio,
        Instant vigenciaFim) {

    public ComandoDeCadastroDeOferta paraComando() {
        return new ComandoDeCadastroDeOferta(
                ofertaId, termo, sinonimos, rotulo, destino, jornada, produtoAlvo,
                ligado(exigeElegibilidade), ligado(vetaInadimplencia), ligado(exigeContaAtiva),
                gatilhos, segmentos, relacionamentos, vigenciaInicio, vigenciaFim);
    }

    private static boolean ligado(Boolean valor) {
        return Boolean.TRUE.equals(valor);
    }
}
