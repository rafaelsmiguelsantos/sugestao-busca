package dev.sugestao.busca.dominio;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O comportamento e o estado de conta do cliente, montados fora da requisição e
 * lidos em 1 a 5 ms. Nada aqui é calculado no caminho de 30 ms.
 *
 * <p>O {@link #NEUTRO} é o caminho alternativo quando a leitura estoura o prazo,
 * e ele codifica as degradações do desenho de uma vez: sem histórico e sem
 * gatilho, a lista vira a popular do segmento — <em>falha aberta</em>; sem
 * elegibilidade concedida e com a conta em estado desconhecido, toda oferta que
 * depende do perfil some — <em>falha fechada</em>. As duas direções estão
 * certas: uma lista pior não machuca ninguém, uma oferta que o cliente não pode
 * contratar machuca.
 *
 * <p><strong>Os quatro mapas de comportamento, e por que são quatro.</strong>
 * {@code afinidadePorJornada} diz "esta pessoa quer crédito";
 * {@code afinidadePorProduto} diz "e dentro de crédito, ela quer consignado";
 * {@code tendenciaPorJornada} diz "e esse interesse está subindo ou esfriando";
 * {@code fadigaPorTermo} diz "e esta linha específica ela ignora". Os quatro
 * responderiam a uma pergunta só se fossem colapsados num número, e foi
 * exatamente o que havia antes: um escalar por jornada, incapaz de distinguir
 * financiamento de empréstimo porque os dois são {@code credito}.
 *
 * <p>O {@code NEUTRO} os deixa vazios, e vazio é a resposta certa para os
 * quatro: sem perfil não há preferência demonstrada, e inventar uma seria
 * personalizar com base em nada.
 */
public record Perfil(
        List<String> historico,
        Set<String> gatilhosAtivos,
        Set<String> produtosContratados,
        Set<String> elegibilidades,
        SituacaoDaConta situacaoDaConta,
        Map<String, Double> afinidadePorJornada,
        Map<String, Double> afinidadePorProduto,
        Map<String, Double> tendenciaPorJornada,
        Map<String, Double> fadigaPorTermo,
        boolean conhecido) {

    public static final Perfil NEUTRO = new Perfil(
            List.of(), Set.of(), Set.of(), Set.of(),
            SituacaoDaConta.DESCONHECIDA,
            Map.of(), Map.of(), Map.of(), Map.of(), false);

    public Perfil {
        historico = historico == null ? List.of() : List.copyOf(historico);
        gatilhosAtivos = gatilhosAtivos == null ? Set.of() : Set.copyOf(gatilhosAtivos);
        produtosContratados = produtosContratados == null ? Set.of() : Set.copyOf(produtosContratados);
        elegibilidades = elegibilidades == null ? Set.of() : Set.copyOf(elegibilidades);
        situacaoDaConta = situacaoDaConta == null ? SituacaoDaConta.DESCONHECIDA : situacaoDaConta;
        afinidadePorJornada = afinidadePorJornada == null ? Map.of() : Map.copyOf(afinidadePorJornada);
        afinidadePorProduto = afinidadePorProduto == null ? Map.of() : Map.copyOf(afinidadePorProduto);
        tendenciaPorJornada = tendenciaPorJornada == null ? Map.of() : Map.copyOf(tendenciaPorJornada);
        fadigaPorTermo = fadigaPorTermo == null ? Map.of() : Map.copyOf(fadigaPorTermo);
    }

    /** Sem comportamento declarado. O cliente existe, mas ainda não demonstrou preferência. */
    public Perfil(
            List<String> historico,
            Set<String> gatilhosAtivos,
            Set<String> produtosContratados,
            Set<String> elegibilidades,
            SituacaoDaConta situacaoDaConta,
            boolean conhecido) {
        this(historico, gatilhosAtivos, produtosContratados, elegibilidades,
                situacaoDaConta, Map.of(), Map.of(), Map.of(), Map.of(), conhecido);
    }

    /**
     * Só com afinidade de jornada.
     *
     * <p>Mantido depois de os outros três mapas entrarem porque a afinidade de
     * jornada é o sinal que se exercita sozinho: quase todo teste de ranking quer
     * dizer "esta pessoa gosta de crédito" sem ter opinião sobre produto,
     * tendência ou fadiga. Com o construtor canônico de dez argumentos, cada um
     * desses testes passaria três {@code Map.of()} que não significam nada — e
     * três argumentos sem significado no meio de uma chamada é como se esconde um
     * argumento com significado.
     */
    public Perfil(
            List<String> historico,
            Set<String> gatilhosAtivos,
            Set<String> produtosContratados,
            Set<String> elegibilidades,
            SituacaoDaConta situacaoDaConta,
            Map<String, Double> afinidadePorJornada,
            boolean conhecido) {
        this(historico, gatilhosAtivos, produtosContratados, elegibilidades,
                situacaoDaConta, afinidadePorJornada, Map.of(), Map.of(), Map.of(), conhecido);
    }

    /**
     * Quanto esta pessoa demonstrou interesse nesta jornada, entre 0 e 1.
     *
     * <p>Vem do comportamento <em>dela</em> — o que ela clicou e contratou —, e
     * não do que a população faz. O {@code ctr} e a {@code taxa_adesao} do
     * índice já respondem a segunda pergunta; esta responde a primeira, e as
     * duas discordam com frequência: o produto mais clicado do banco pode ser
     * exatamente o que este cliente ignora há meses.
     */
    public double afinidadeCom(String jornada) {
        return valor(afinidadePorJornada, jornada, 0.0);
    }

    /**
     * Quanto esta pessoa demonstrou interesse neste produto, entre 0 e 1.
     *
     * <p>É o nível que distingue consignado de empréstimo dentro de
     * {@code credito}. Quase sempre vale 0, e isso não é defeito: evidência por
     * produto é escassa por construção, e o piso de confiança mais baixo em
     * {@code PesosDeAfinidade} é o que a torna alcançável sem a tornar barata.
     */
    public double afinidadeComProduto(String produto) {
        return valor(afinidadePorProduto, produto, 0.0);
    }

    /**
     * Se o interesse nesta jornada está subindo (>1), estável (=1) ou esfriando
     * (<1).
     *
     * <p>O padrão é <strong>1,0 e não 0,0</strong>, e a diferença importa: isto é
     * um multiplicador, e 0 zeraria a linha de quem não tem evidência suficiente
     * para ter tendência medida — que é a maioria das pessoas na maior parte do
     * tempo.
     */
    public double tendenciaCom(String jornada) {
        return valor(tendenciaPorJornada, jornada, 1.0);
    }

    /**
     * O quanto esta linha deve perder por ter sido exibida e ignorada, entre 0 e
     * o teto de {@code PesosDeFadiga}.
     *
     * <p>A chave é o termo, e não o identificador de oferta, porque é o termo que
     * aparece nos dois eventos que alimentam a conta: a impressão carrega
     * {@code termosExibidos} e o clique carrega {@code termo}. Trocar para
     * identificador de oferta exigiria o app mandar a lista de ofertas exibidas —
     * mudança de contrato no cliente para ganhar precisão em linha de catálogo,
     * perdendo a conta inteira para linha popular, que não tem oferta.
     */
    public double fadigaDe(String termo) {
        // Normaliza na LEITURA, e isto é a correção de um defeito que custou uma
        // verificação verde testando nada.
        //
        // A agregação guarda a chave normalizada — "antecipacao de limite" — pela
        // mesma razão que normaliza a jornada: senão duas grafias do mesmo termo
        // viram dois baldes. Mas o Rankeador consulta com `candidato.termo()`,
        // que é o termo CRU do índice: "antecipação de limite", com acento.
        //
        // A chave nunca casava para nenhum termo com acento ou maiúscula, ou
        // seja, para a maior parte do catálogo. A fadiga era calculada, gravada
        // no perfil, lida a cada tecla — e não rebaixava nada. Falha silenciosa,
        // e da mesma família da que a afinidade já teve: "um app que manda
        // 'Crédito' cria um balde que nunca casa com o jornada_s do índice".
        //
        // Normalizar aqui e não no chamador é deliberado: o mapa é guardado
        // normalizado, então quem o acessa normaliza. Deixar isso para o
        // chamador é esperar que todos os chamadores futuros lembrem.
        return valor(fadigaPorTermo, Normalizacao.aplicar(termo), 0.0);
    }

    private static double valor(Map<String, Double> mapa, String chave, double ausente) {
        if (chave == null || chave.isBlank()) {
            return ausente;
        }
        return mapa.getOrDefault(chave, ausente);
    }

    public boolean jaContratou(String produto) {
        return produto != null && !produto.isBlank() && produtosContratados.contains(produto);
    }

    public boolean elegivelPara(String produto) {
        return produto != null && !produto.isBlank() && elegibilidades.contains(produto);
    }

    public boolean temAlgumGatilho(List<String> gatilhos) {
        return gatilhos.stream().anyMatch(gatilhosAtivos::contains);
    }
}
