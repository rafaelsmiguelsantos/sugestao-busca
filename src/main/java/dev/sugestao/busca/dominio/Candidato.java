package dev.sugestao.busca.dominio;

import java.util.List;
import java.util.Objects;

/**
 * Uma linha em potencial, antes da peneira e da mistura. Carrega o que o perfil
 * precisa para julgá-la: o produto que ela empurra, o que ela exige do cliente,
 * e quais gatilhos a tornam oportuna.
 *
 * <p>{@code clientesDistintos} viaja junto de propósito. O piso de k-anonimato é
 * um {@code fq} no índice — este campo é só a rede de segurança do BFF, para o
 * caso de uma coleção ter sido publicada com o invariante violado.
 *
 * <p>{@code ofertaId} é a identidade que a jornada dona atribuiu à oferta, e é o
 * único campo desta classe que serve a quem <em>consome</em> a API em vez de a
 * quem a lê na tela. Sem ele, uma jornada que recebe esta lista tem texto e
 * caminho, e nada com que voltar ao seu próprio cadastro — ela teria de casar
 * por {@code termo}, que é texto de exibição e muda sem aviso. É vazio de
 * propósito em três casos: termo aprendido do log (é consulta, não oferta), item
 * de navegação ou entidade sem produto, e linha vinda do histórico do cliente.
 */
public record Candidato(
        String ofertaId,
        String termo,
        String rotulo,
        String destino,
        String jornada,
        String produtoAlvo,
        Restricoes restricoes,
        List<String> gatilhos,
        int clientesDistintos,
        double relevancia,
        Origem origem,
        Procedencia procedencia) {

    public Candidato {
        Objects.requireNonNull(termo, "termo");
        ofertaId = ofertaId == null ? "" : ofertaId.strip();
        restricoes = restricoes == null ? Restricoes.NENHUMA : restricoes;
        gatilhos = gatilhos == null ? List.of() : List.copyOf(gatilhos);
        origem = origem == null ? Origem.POPULAR : origem;
        procedencia = procedencia == null ? procedenciaDe(origem) : procedencia;
    }

    /**
     * Candidato cuja procedência se deduz da origem.
     *
     * <p>Serve a quem cria a linha sabendo de onde ela vem — histórico, e os
     * testes. O adaptador do índice <em>não</em> usa: lá a procedência é lida do
     * campo {@code tipo} do documento, porque é a única fonte que distingue
     * termo aprendido de oferta cadastrada.
     */
    public Candidato(
            String ofertaId,
            String termo,
            String rotulo,
            String destino,
            String jornada,
            String produtoAlvo,
            Restricoes restricoes,
            List<String> gatilhos,
            int clientesDistintos,
            double relevancia,
            Origem origem) {

        this(ofertaId, termo, rotulo, destino, jornada, produtoAlvo, restricoes,
                gatilhos, clientesDistintos, relevancia, origem, procedenciaDe(origem));
    }

    /**
     * O candidato que não é oferta: histórico do cliente e termo vindo do log.
     * Existe para que a ausência de identidade seja uma escolha visível na
     * chamada, e não um {@code ""} a mais numa lista de onze argumentos.
     */
    public Candidato(
            String termo,
            String rotulo,
            String destino,
            String jornada,
            String produtoAlvo,
            Restricoes restricoes,
            List<String> gatilhos,
            int clientesDistintos,
            double relevancia,
            Origem origem) {

        this("", termo, rotulo, destino, jornada, produtoAlvo,
                restricoes, gatilhos, clientesDistintos, relevancia, origem);
    }

    /**
     * A procedência que a origem implica, quando ninguém a declarou.
     *
     * <p>{@link Origem#EVENTO} cai em {@link Procedencia#LOG} — a opção mais
     * restritiva — e isso é escolha, não descuido. Evento não se cria: ele é
     * resultado de {@link #com(Origem)} sobre um candidato que já existia, e
     * aquele caminho preserva a procedência verdadeira. Se um dia alguém
     * construir um evento direto, é melhor que ele responda por um piso que não
     * precisava do que escape de um que precisava.
     */
    private static Procedencia procedenciaDe(Origem origem) {
        return switch (origem) {
            case HISTORICO -> Procedencia.CLIENTE;
            case CATALOGO -> Procedencia.CATALOGO;
            case POPULAR, EVENTO -> Procedencia.LOG;
        };
    }

    /**
     * Esta linha é uma oferta que uma jornada pode acionar pelo identificador.
     *
     * <p>Exige as duas coisas juntas porque identificador sem produto é cadastro
     * pela metade: o {@link Rankeador} usa {@code produtoAlvo} para decidir que
     * ela lidera a jornada, e a {@link Peneira} usa
     * {@link #chaveDeElegibilidade()} para julgá-la. Anunciar como oferta algo
     * que nenhuma das duas reconhece seria prometer ao consumidor uma coisa que
     * o resto do fluxo não trata como tal.
     */
    public boolean ehOferta() {
        return !vazio(ofertaId) && !vazio(produtoAlvo);
    }

    /** A chave de deduplicação. Duas redações da mesma intenção colidem aqui. */
    public String chave() {
        return Normalizacao.aplicar(termo);
    }

    /**
     * Troca a origem e <strong>preserva a procedência</strong>. É por esta linha
     * que o piso de k-anonimato continua valendo depois de uma promoção a
     * evento: o gatilho muda onde a linha aparece, não de onde o dado veio.
     */
    public Candidato com(Origem outra) {
        return new Candidato(ofertaId, termo, rotulo, destino, jornada, produtoAlvo,
                restricoes, gatilhos, clientesDistintos, relevancia, outra, procedencia);
    }

    public Candidato com(double outraRelevancia) {
        return new Candidato(ofertaId, termo, rotulo, destino, jornada, produtoAlvo,
                restricoes, gatilhos, clientesDistintos, outraRelevancia, origem, procedencia);
    }

    /**
     * Herda do candidato descartado o que o vencedor não tem. Quando o termo
     * vem do histórico e também do catálogo, o histórico ganha a linha — mas
     * perderia o destino real e voltaria a cair numa busca por texto. Aqui ele
     * fica com a origem dele e com o caminho do catálogo.
     *
     * <p>O identificador da oferta é herdado junto, e é o caso que mais importa:
     * sem isto, uma busca que o cliente já fez antes apareceria como linha de
     * histórico sem identidade, e a jornada consumidora perderia justamente a
     * oferta que ela tem mais motivo para acionar — a que a pessoa já procurou.
     *
     * <p>Rótulo, destino e jornada se herdam porque são descrição: duas redações
     * do mesmo termo descrevem a mesma coisa. <strong>Contador de clientes
     * distintos não se herda</strong>, e é a única exceção. Ele era
     * {@code Math.max} das duas pontas, o que parecia conservador e era o
     * contrário: um termo do log com nove clientes distintos, colidindo com um
     * item de catálogo, saía da mistura com o contador do catálogo e passava
     * pelo piso de k-anonimato sem nunca ter tido trinta pessoas por trás. Cada
     * candidato responde pelo próprio número, medido sobre a própria população.
     *
     * <p>A procedência também fica com o vencedor, pelo mesmo motivo: ela diz
     * se o piso se aplica, e herdá-la do descartado faria a regra de privacidade
     * depender de quem ganhou a deduplicação.
     */
    public Candidato completarCom(Candidato outro) {
        return new Candidato(
                vazio(ofertaId) ? outro.ofertaId() : ofertaId,
                termo,
                vazio(rotulo) ? outro.rotulo() : rotulo,
                vazio(destino) ? outro.destino() : destino,
                // Jornada.ausente e nao vazio(): a linha do historico chega com
                // "busca", que nao e vazio e tambem nao e jornada. Sem isto ela
                // herdava identificador e produto da oferta e ficava com a
                // jornada sentinela — oferta acionavel que o ranking tratava
                // como sem jornada, na linha de sinal mais forte do conjunto.
                Jornada.ausente(jornada) ? outro.jornada() : jornada,
                vazio(produtoAlvo) ? outro.produtoAlvo() : produtoAlvo,
                restricoes,
                gatilhos.isEmpty() ? outro.gatilhos() : gatilhos,
                clientesDistintos,
                Math.max(relevancia, outro.relevancia()),
                origem,
                procedencia);
    }

    /**
     * Sob que nome a elegibilidade deste candidato é concedida.
     *
     * <p>Quase sempre é o produto. Mas há item de catálogo que exige
     * elegibilidade e não empurra produto nenhum — portabilidade de salário é um
     * deles. Sem este desvio, "exige elegibilidade" com produto vazio nunca
     * encontraria concessão e o item sumiria da lista para todo mundo, para
     * sempre, sem ninguém perceber: a falha fechada estaria certa na direção e
     * errada no alvo.
     */
    public String chaveDeElegibilidade() {
        return vazio(produtoAlvo) ? chave() : produtoAlvo;
    }

    private static boolean vazio(String texto) {
        return texto == null || texto.isBlank();
    }

    public Sugestao paraSugestao() {
        return new Sugestao(ofertaId, termo, rotulo, destino, jornada, produtoAlvo,
                relevancia, origem);
    }
}
