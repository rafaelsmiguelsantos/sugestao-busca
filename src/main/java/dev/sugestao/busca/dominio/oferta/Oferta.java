package dev.sugestao.busca.dominio.oferta;

import dev.sugestao.busca.dominio.Jornada;
import dev.sugestao.busca.dominio.Normalizacao;
import dev.sugestao.busca.dominio.Relacionamento;
import dev.sugestao.busca.dominio.Restricoes;
import dev.sugestao.busca.dominio.Segmento;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Uma oferta cadastrada pela jornada que a possui.
 *
 * <p>É o lado de escrita do que a busca devolve. Até existir, oferta entrava no
 * índice por um script lendo um arquivo JSON curado à mão — o que serve para
 * semear uma demonstração e não serve para uma jornada que lança um produto na
 * terça-feira.
 *
 * <p><strong>O que esta classe deliberadamente não aceita:</strong> {@code ctr},
 * {@code taxaAdesao}, {@code clientesDistintos} e {@code freq}. São os quatro
 * números que mais mexem no ranking, e nenhum deles é declarável. Eles são
 * medidos pelo caminho de aprendizado a partir de impressão, clique e adesão
 * reais. Se o cadastro os aceitasse, a jornada com a oferta mais fraca e o
 * integrador mais otimista ficaria em primeiro lugar, e o ranking passaria a
 * medir confiança de quem preenche formulário. A oferta nova entra com os quatro
 * em zero e sobe — ou não — pelo que acontecer com ela.
 *
 * <p>A validação acumula tudo antes de recusar. Ver {@link OfertaInvalida}.
 */
public record Oferta(
        String ofertaId,
        String termo,
        List<String> sinonimos,
        String rotulo,
        String destino,
        String jornada,
        String produtoAlvo,
        Restricoes restricoes,
        List<String> gatilhos,
        Set<Segmento> segmentos,
        Set<Relacionamento> relacionamentos,
        Instant vigenciaInicio,
        Instant vigenciaFim) {

    /**
     * Maiúsculas, dígitos, hífen e sublinhado, começando por alfanumérico.
     *
     * <p>Restritivo porque este identificador vira parte de um id de documento
     * no Solr e de um caminho de URL em {@code GET /v1/ofertas/{id}}. Aceitar
     * barra, ponto ou espaço aqui seria aceitar que o identificador de uma
     * oferta mude o caminho que o consulta.
     */
    private static final Pattern ID_VALIDO = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{2,63}$");

    /** Mesma forma de um código de produto de sistema bancário: sem hífen. */
    private static final Pattern PRODUTO_VALIDO = Pattern.compile("^[A-Z0-9][A-Z0-9_]{1,63}$");

    /** Gatilho é nome de evento, e o perfil o compara literal. */
    private static final Pattern GATILHO_VALIDO = Pattern.compile("^[a-z0-9_]{3,40}$");

    private static final int TETO_DE_SINONIMOS = 20;
    private static final int TETO_DE_GATILHOS = 10;
    private static final int TETO_DO_TERMO = 120;

    /**
     * O único construtor público, e a única porta de entrada de oferta no
     * sistema. Recebe texto cru de propósito: a conversão de
     * {@code segmento}/{@code relacionamento} é parte da validação, e fazê-la na
     * borda significaria duas listas de violações para o consumidor juntar.
     *
     * @throws OfertaInvalida com todas as violações encontradas
     */
    public static Oferta de(
            String ofertaId,
            String termo,
            List<String> sinonimos,
            String rotulo,
            String destino,
            String jornada,
            String produtoAlvo,
            boolean exigeElegibilidade,
            boolean vetaInadimplencia,
            boolean exigeContaAtiva,
            List<String> gatilhos,
            List<String> segmentos,
            List<String> relacionamentos,
            Instant vigenciaInicio,
            Instant vigenciaFim) {

        var violacoes = new ArrayList<Violacao>();

        String id = texto(ofertaId).toUpperCase();
        if (id.isEmpty()) {
            violacoes.add(Violacao.obrigatorio("ofertaId"));
        } else if (!ID_VALIDO.matcher(id).matches()) {
            violacoes.add(Violacao.formato("ofertaId",
                    "3 a 64 caracteres [A-Z0-9_-], comecando por letra ou digito"));
        }

        // O termo é normalizado aqui pela mesma função que normaliza o prefixo
        // digitado. Se as duas divergissem, a oferta entraria no índice sob uma
        // chave que nenhuma busca produz: cadastro aceito, oferta inalcançável.
        String termoNormalizado = Normalizacao.aplicar(texto(termo));
        if (termoNormalizado.isEmpty()) {
            violacoes.add(Violacao.obrigatorio("termo"));
        } else if (termoNormalizado.length() > TETO_DO_TERMO) {
            violacoes.add(Violacao.formato("termo", "no maximo " + TETO_DO_TERMO + " caracteres"));
        }

        var sinonimosLimpos = normalizarLista(sinonimos);
        if (sinonimosLimpos.size() > TETO_DE_SINONIMOS) {
            violacoes.add(Violacao.acimaDoTeto("sinonimos", TETO_DE_SINONIMOS));
        }

        String destinoLimpo = texto(destino);
        if (destinoLimpo.isEmpty()) {
            violacoes.add(Violacao.obrigatorio("destino"));
        } else if (!destinoLimpo.startsWith("/") || destinoLimpo.startsWith("//")) {
            // Caminho relativo, nunca URL absoluta. O destino é devolvido ao app
            // e o app navega para ele; aceitar "https://" aqui faria do cadastro
            // de oferta um redirecionador aberto, com o nome do banco na frente.
            // "//" é barrado junto porque o navegador o trata como absoluto.
            violacoes.add(Violacao.formato("destino",
                    "caminho relativo comecando com '/', sem esquema e sem '//'"));
        }

        // Oferta cadastrada no sentinela de "sem jornada" nunca lideraria
        // jornada nenhuma e nunca receberia afinidade do cliente: pagaria o
        // custo de existir sem receber nenhum dos beneficios. Recusar e mais
        // honesto que aceitar e nao funcionar.
        String jornadaNormalizada = Normalizacao.aplicar(texto(jornada));
        if (jornadaNormalizada.isEmpty()) {
            violacoes.add(Violacao.obrigatorio("jornada"));
        } else if (Jornada.ausente(jornadaNormalizada)) {
            violacoes.add(Violacao.reservado("jornada",
                    "'" + Jornada.AUSENTE + "' e reservado para termo sem jornada"
                            + " e nunca lidera ranking"));
        }

        String produto = texto(produtoAlvo).toUpperCase();
        if (produto.isEmpty()) {
            // Oferta sem produto não é oferta: a Peneira não tem o que julgar e
            // o Rankeador não tem o que promover. Para item de navegação sem
            // produto existe o catálogo curado, que não passa por aqui.
            violacoes.add(Violacao.obrigatorio("produtoAlvo"));
        } else if (!PRODUTO_VALIDO.matcher(produto).matches()) {
            violacoes.add(Violacao.formato("produtoAlvo",
                    "2 a 64 caracteres [A-Z0-9_], comecando por letra ou digito"));
        }

        var gatilhosLimpos = new ArrayList<String>();
        for (String gatilho : gatilhos == null ? List.<String>of() : gatilhos) {
            String limpo = texto(gatilho).toLowerCase();
            if (limpo.isEmpty()) {
                continue;
            }
            if (!GATILHO_VALIDO.matcher(limpo).matches()) {
                violacoes.add(Violacao.formato("gatilhos",
                        "cada gatilho tem 3 a 40 caracteres [a-z0-9_]; recebido '" + limpo + "'"));
            } else {
                gatilhosLimpos.add(limpo);
            }
        }
        if (gatilhosLimpos.size() > TETO_DE_GATILHOS) {
            violacoes.add(Violacao.acimaDoTeto("gatilhos", TETO_DE_GATILHOS));
        }

        var segmentosLidos = new LinkedHashSet<Segmento>();
        if (segmentos == null || segmentos.isEmpty()) {
            // Sem segmento o documento fica fora do {!terms f=segmento_ss} e
            // ninguém o encontra. Assumir PF aqui publicaria para pessoa física
            // uma oferta que talvez fosse de PJ.
            violacoes.add(Violacao.obrigatorio("segmentos"));
        } else {
            for (String bruto : segmentos) {
                Segmento.talvez(bruto).ifPresentOrElse(
                        segmentosLidos::add,
                        () -> violacoes.add(Violacao.foraDoDominio("segmentos", texto(bruto), "PF, PJ")));
            }
        }

        var faixasLidas = new LinkedHashSet<Relacionamento>();
        if (relacionamentos == null || relacionamentos.isEmpty()) {
            violacoes.add(Violacao.obrigatorio("relacionamentos"));
        } else {
            for (String bruto : relacionamentos) {
                Relacionamento.talvez(bruto).ifPresentOrElse(
                        faixasLidas::add,
                        () -> violacoes.add(Violacao.foraDoDominio("relacionamentos", texto(bruto),
                                "VAREJO, UNICLASS, PERSONNALITE, PRIVATE")));
            }
        }

        if (vigenciaInicio == null) {
            violacoes.add(Violacao.obrigatorio("vigenciaInicio"));
        }
        if (vigenciaFim == null) {
            violacoes.add(Violacao.obrigatorio("vigenciaFim"));
        }
        if (vigenciaInicio != null && vigenciaFim != null && !vigenciaFim.isAfter(vigenciaInicio)) {
            // Não se verifica se o fim já passou: cadastrar oferta encerrada é
            // legítimo (correção retroativa, encerramento antecipado), e o fq de
            // vigência já a mantém fora da busca. Fim antes do início, não: isso
            // é oferta que nunca vale, e aceitar seria aceitar um cadastro que
            // não faz nada.
            violacoes.add(new Violacao("vigenciaFim", Violacao.ORDEM_INVALIDA,
                    "deve ser posterior a vigenciaInicio"));
        }

        if (!violacoes.isEmpty()) {
            throw new OfertaInvalida(violacoes);
        }

        return new Oferta(
                id,
                termoNormalizado,
                List.copyOf(sinonimosLimpos),
                texto(rotulo),
                destinoLimpo,
                jornadaNormalizada,
                produto,
                new Restricoes(exigeElegibilidade, vetaInadimplencia, exigeContaAtiva),
                List.copyOf(gatilhosLimpos),
                Set.copyOf(segmentosLidos),
                Set.copyOf(faixasLidas),
                vigenciaInicio,
                vigenciaFim);
    }

    /** O id do documento no índice. Prefixo próprio para não colidir com {@code cat-} nem {@code log-}. */
    public String idDoDocumento() {
        return "ofr-" + ofertaId.toLowerCase();
    }

    private static List<String> normalizarLista(List<String> valores) {
        if (valores == null) {
            return List.of();
        }
        var limpos = new ArrayList<String>(valores.size());
        for (String valor : valores) {
            String normalizado = Normalizacao.aplicar(texto(valor));
            if (!normalizado.isEmpty() && !limpos.contains(normalizado)) {
                limpos.add(normalizado);
            }
        }
        return limpos;
    }

    private static String texto(String valor) {
        return valor == null ? "" : valor.strip();
    }
}
