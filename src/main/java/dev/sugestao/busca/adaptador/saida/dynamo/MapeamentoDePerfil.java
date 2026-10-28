package dev.sugestao.busca.adaptador.saida.dynamo;

import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.SituacaoDaConta;
import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Traduz entre {@link Perfil} e itens do DynamoDB.
 *
 * <p><strong>Funções puras, de propósito.</strong> Nada aqui toca a rede, e é por
 * isso que esta classe existe separada do adaptador: o mapeamento é onde moram os
 * defeitos — campo esquecido, tipo trocado, vazio confundido com ausente — e
 * testá-lo exige apenas um {@code Map}. Os testes do projeto continuam rodando
 * sem banco no ar.
 *
 * <h2>Por que o perfil é DOIS itens, e não um</h2>
 *
 * <p>O {@link Perfil} parece um registro só e são dois domínios de propriedade:
 *
 * <table border="1">
 *   <caption>Quem escreve o quê</caption>
 *   <tr><th>{@code SK = COMPORTAMENTO}</th><th>{@code SK = CADASTRO}</th></tr>
 *   <tr>
 *     <td>histórico, afinidade por jornada, afinidade por produto, tendência,
 *         fadiga — <em>derivados do nosso log de eventos</em>, e reconstruíveis
 *         a partir dele</td>
 *     <td>gatilhos, produtos contratados, elegibilidades, situação da conta —
 *         <em>fato de outro sistema</em>, que apenas cacheamos e não temos como
 *         recalcular</td>
 *   </tr>
 * </table>
 *
 * <p>Um item só, gravado com {@code PutItem}, reintroduziria o defeito que
 * {@code PerfisEmMemoria.mesclar} documenta: <em>"uma busca qualquer apagaria a
 * inadimplência do cliente e o crédito novo voltaria a ser oferecido na tecla
 * seguinte"</em>. Em memória o {@code merge} é atômico dentro de uma JVM. Com dois
 * produtores escrevendo o registro inteiro, é <em>lost update</em> — e o que se
 * perde é a inadimplência.
 *
 * <p>Separar em dois itens na mesma partição faz cada escritor ficar
 * <strong>fisicamente incapaz</strong> de apagar o campo do outro. A regra deixa
 * de depender de alguém lembrar, que é o mesmo princípio do construtor do
 * {@code Substituto} recusar restrição em vez de confiar na revisão.
 *
 * <p>A leitura paga uma {@code Query} em vez de um {@code GetItem} — mesma
 * partição, uma ida só, e os dois itens voltam juntos.
 */
final class MapeamentoDePerfil {

    static final String PK = "pk";
    static final String SK = "sk";

    static final String SK_COMPORTAMENTO = "COMPORTAMENTO";
    static final String SK_CADASTRO = "CADASTRO";

    // Prefixo na chave de partição. Não é decoração: numa tabela única o mesmo
    // atributo guarda entidades diferentes, e sem o prefixo um clienteId que
    // colida com outro tipo de identificador vira sobrescrita silenciosa.
    private static final String PREFIXO = "CLIENTE#";

    private MapeamentoDePerfil() {
    }

    static Map<String, AttributeValue> chave(String clienteId, String sk) {
        return Map.of(
                PK, texto(PREFIXO + clienteId),
                SK, texto(sk));
    }

    static AttributeValue chaveDeParticao(String clienteId) {
        return texto(PREFIXO + clienteId);
    }

    /**
     * Os atributos que a rodada de aprendizado escreve no item
     * {@code COMPORTAMENTO}.
     *
     * <p>O histórico fica de fora quando vem vazio, e isso <strong>não</strong> é
     * economia: reproduz a regra de {@code PerfisEmMemoria}, onde vazio significa
     * "esta janela não teve nada a dizer", nunca "esta pessoa não tem nada". Um
     * cliente que clicou e não digitou em 24 h chegava com histórico vazio, e o
     * vazio era gravado sobre o histórico guardado — um clique apagava a camada
     * HISTORICO da pessoa, para sempre.
     *
     * <p>As afinidades, a tendência e a fadiga sobrescrevem <em>inclusive com
     * vazio</em>, também como em memória: o decaimento mora dentro do cálculo, por
     * idade de evento, então o que chega aqui já é a verdade atual e um mapa vazio
     * quer dizer "nenhum clique em três meses" — afinidade zero de verdade.
     */
    static Map<String, AttributeValue> atributosDeComportamento(ComportamentoDoCliente c) {
        var atributos = new LinkedHashMap<String, AttributeValue>();
        if (!c.historico().isEmpty()) {
            atributos.put("historico", listaDeTextos(c.historico()));
        }
        atributos.put("afinidadePorJornada", mapaDeNumeros(c.afinidadePorJornada()));
        atributos.put("afinidadePorProduto", mapaDeNumeros(c.afinidadePorProduto()));
        atributos.put("tendenciaPorJornada", mapaDeNumeros(c.tendenciaPorJornada()));
        atributos.put("fadigaPorTermo", mapaDeNumeros(c.fadigaPorTermo()));
        return atributos;
    }

    /**
     * Monta o perfil a partir dos itens devolvidos pela {@code Query} — zero, um
     * ou dois.
     *
     * <p>É aqui que o {@code mesclar} de {@code PerfisEmMemoria} vira montagem na
     * leitura. A diferença é que lá a regra era uma convenção que o próximo
     * desenvolvedor podia quebrar; aqui os campos vêm de itens distintos e não há
     * como confundi-los.
     *
     * <p>Nenhum item devolve {@link Perfil#NEUTRO}, que carrega
     * {@code conhecido = false} — e é o que faz a lista sair genérica em vez de
     * personalizada, sem ninguém tratar nulo.
     */
    static Perfil perfilDeItens(List<Map<String, AttributeValue>> itens) {
        if (itens == null || itens.isEmpty()) {
            return Perfil.NEUTRO;
        }

        Map<String, AttributeValue> comportamento = Map.of();
        Map<String, AttributeValue> cadastro = Map.of();
        for (var item : itens) {
            var sk = item.get(SK);
            if (sk == null || sk.s() == null) {
                continue;
            }
            switch (sk.s()) {
                case SK_COMPORTAMENTO -> comportamento = item;
                case SK_CADASTRO -> cadastro = item;
                default -> {
                    // Item de outro tipo na mesma partição. Ignorar em vez de
                    // falhar: a tabela é única e vai receber outras entidades.
                }
            }
        }

        return new Perfil(
                textos(comportamento, "historico"),
                conjunto(cadastro, "gatilhosAtivos"),
                conjunto(cadastro, "produtosContratados"),
                conjunto(cadastro, "elegibilidades"),
                situacao(cadastro),
                numeros(comportamento, "afinidadePorJornada"),
                numeros(comportamento, "afinidadePorProduto"),
                numeros(comportamento, "tendenciaPorJornada"),
                numeros(comportamento, "fadigaPorTermo"),
                true);
    }

    // ---------------------------------------------------------------- escrita

    static AttributeValue texto(String valor) {
        return AttributeValue.fromS(valor);
    }

    static AttributeValue numero(long valor) {
        return AttributeValue.fromN(Long.toString(valor));
    }

    /**
     * Lista, não {@code SS}. O DynamoDB <strong>recusa</strong> um conjunto de
     * strings vazio — e aqui o vazio é um estado legítimo e frequente. Lista
     * aceita vazio, e a ordem não importa para quem lê.
     */
    static AttributeValue listaDeTextos(java.util.Collection<String> valores) {
        var lista = new ArrayList<AttributeValue>(valores.size());
        for (var v : valores) {
            lista.add(texto(v));
        }
        return AttributeValue.fromL(lista);
    }

    static AttributeValue mapaDeNumeros(Map<String, Double> valores) {
        var mapa = new LinkedHashMap<String, AttributeValue>();
        for (var e : valores.entrySet()) {
            // Double.toString e nao formatacao com locale: virgula decimal faria
            // o DynamoDB recusar o numero, e o erro sairia longe daqui.
            mapa.put(e.getKey(), AttributeValue.fromN(Double.toString(e.getValue())));
        }
        return AttributeValue.fromM(mapa);
    }

    // ---------------------------------------------------------------- leitura

    private static List<String> textos(Map<String, AttributeValue> item, String campo) {
        var v = item.get(campo);
        if (v == null || v.l() == null) {
            return List.of();
        }
        var lista = new ArrayList<String>(v.l().size());
        for (var elemento : v.l()) {
            if (elemento.s() != null) {
                lista.add(elemento.s());
            }
        }
        return List.copyOf(lista);
    }

    private static Set<String> conjunto(Map<String, AttributeValue> item, String campo) {
        return Set.copyOf(new LinkedHashSet<>(textos(item, campo)));
    }

    private static Map<String, Double> numeros(Map<String, AttributeValue> item, String campo) {
        var v = item.get(campo);
        if (v == null || v.m() == null) {
            return Map.of();
        }
        var mapa = new LinkedHashMap<String, Double>();
        for (var e : v.m().entrySet()) {
            var n = e.getValue().n();
            if (n != null) {
                mapa.put(e.getKey(), Double.valueOf(n));
            }
        }
        return Map.copyOf(mapa);
    }

    /**
     * Valor desconhecido ou ausente cai em {@link SituacaoDaConta#DESCONHECIDA}, e
     * essa é a direção segura: desconhecida não é operante, então a oferta
     * condicionada não aparece até alguém que saiba dizer. Um valor novo no enum
     * do produtor não deve derrubar a busca de ninguém.
     */
    private static SituacaoDaConta situacao(Map<String, AttributeValue> item) {
        var v = item.get("situacaoDaConta");
        if (v == null || v.s() == null) {
            return SituacaoDaConta.DESCONHECIDA;
        }
        try {
            return SituacaoDaConta.valueOf(v.s());
        } catch (IllegalArgumentException e) {
            return SituacaoDaConta.DESCONHECIDA;
        }
    }
}
