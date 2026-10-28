package dev.sugestao.busca.adaptador.saida.perfil;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.Perfil;

import java.time.Duration;

/**
 * O perfil guardado pelo tempo de uma digitação, como decorador da porta de
 * leitura.
 *
 * <p><strong>O problema.</strong> O perfil é lido <em>a cada tecla</em>. Quem
 * digita "financiamento" gasta dez teclas, e são dez leituras do mesmo dado —
 * que não mudou entre a primeira e a décima, porque o que o produz roda a cada
 * 15 segundos, fora da requisição. Em 60 milhões de buscas por dia isso é uma
 * multiplicação por dez do acesso a um armazenamento de documento.
 *
 * <p><strong>E o que ele honestamente não ganha hoje.</strong> Nada em latência.
 * O perfil mora num {@link java.util.concurrent.ConcurrentHashMap} neste
 * processo, e ler um mapa local não custa o bastante para medir. O ganho de
 * custo é na forma de produção, onde a porta vai a um DynamoDB. Escrever isso
 * aqui é o que impede alguém de ligar o cache, não ver diferença no p99 e
 * concluir que o recurso não serve.
 *
 * <p><strong>O que ele ganha hoje, e não é custo: estabilidade.</strong> A
 * agregação reescreve perfis a cada 15 segundos. Uma rodada que caia no meio da
 * digitação pode reordenar a lista por um motivo que não tem relação nenhuma com
 * a letra que a pessoa acabou de digitar — afinidade recalculada, fadiga nova,
 * tendência que cruzou o piso. Com um instantâneo por sessão, "digitei uma letra
 * e a lista embaralhou" deixa de ser possível. É o tipo de defeito que não gera
 * ticket e corrói confiança na caixa de busca.
 *
 * <h2>Por que não invalida na escrita</h2>
 *
 * <p>Seria fácil: este objeto poderia implementar {@code EscritorDePerfil}
 * também e derrubar a chave de cada cliente que a agregação tocou. Foi recusado,
 * e a razão é que isso <strong>desfaz o segundo benefício para melhorar um
 * terceiro que ninguém pediu</strong> — a invalidação por escrita devolve
 * exatamente a reordenação no meio da digitação que o cache existe para impedir.
 *
 * <p>O preço de não invalidar é declarado: um clique agora não zera a fadiga até
 * o TTL vencer. Com TTL de segundos contra sinais de janela de 30 e 90 dias,
 * isso é indistinguível de zero. Consistência dentro de uma sessão vale mais que
 * reatividade de 200 ms num sinal medido em semanas.
 *
 * <p>E o teto do TTL é código, não conselho: ver
 * {@code SugestaoProperties.CacheDePerfil}. Um TTL de minutos esconderia a
 * agregação, e o sistema continuaria aprendendo e pararia de usar o que aprendeu
 * sem erro em lugar nenhum.
 *
 * <h2>O que ele preserva da porta</h2>
 *
 * <p><strong>Falha não é guardada.</strong> Quando o delegado lança, o Caffeine
 * propaga a exceção e não grava nada — então o caso de uso continua recebendo a
 * falha, continua convertendo em {@code Colheita.FALHOU} e continua devolvendo
 * lista degradada. Um cache que memorizasse a falha transformaria um soluço de
 * rede em segundos de lista degradada para a mesma pessoa.
 *
 * <p><strong>{@link Perfil#NEUTRO} é guardado, e de propósito.</strong> Cliente
 * sem perfil montado é o caso que mais se beneficia: ele é cliente novo, está
 * digitando pela primeira vez, e cada tecla dele seria uma consulta que volta
 * dizendo "não existe".
 *
 * <p>Como o {@code CatalogoComDisjuntor}, este arquivo é novo e <em>nada mais
 * muda</em>: o caso de uso recebe {@code RepositorioDePerfil} e não sabe que
 * existe cache; o {@code PerfisEmMemoria} não sabe que está sendo decorado; e a
 * porta de escrita continua apontando direto para o adaptador, sem passar por
 * aqui. Quem decide empilhar é a classe de configuração.
 */
public class PerfisComCache implements RepositorioDePerfil {

    private final RepositorioDePerfil delegado;
    private final Cache<String, Perfil> cache;

    public PerfisComCache(RepositorioDePerfil delegado, Duration ttl, int tetoDeChaves) {
        this(delegado, ttl, tetoDeChaves, Ticker.systemTicker());
    }

    /**
     * Com relógio injetado, para o teste poder afirmar a expiração sem dormir.
     *
     * <p>Um {@code Thread.sleep} de 50 ms num teste de TTL é a receita do teste
     * intermitente: ele passa na máquina de quem escreveu e falha na pipeline
     * carregada, e aí alguém aumenta o sleep em vez de olhar. Com o relógio como
     * parâmetro, o avanço do tempo é uma chamada de método e o teste é
     * determinístico.
     */
    PerfisComCache(RepositorioDePerfil delegado, Duration ttl, int tetoDeChaves, Ticker ticker) {
        this.delegado = delegado;
        this.cache = Caffeine.newBuilder()
                .maximumSize(tetoDeChaves)
                // Por escrita, e não por acesso. Por acesso, quem digita sem
                // parar renovaria a entrada indefinidamente e o perfil dessa
                // pessoa nunca mais seria lido — o limite deixaria de ser "uma
                // sessão de digitação" e passaria a ser "enquanto ela digitar".
                .expireAfterWrite(ttl)
                .ticker(ticker)
                .recordStats()
                .build();
    }

    @Override
    public Perfil porCliente(String clienteId) {
        return cache.get(clienteId, delegado::porCliente);
    }

    /**
     * Força a manutenção pendente. Existe para o teste, e por um motivo que vale
     * saber.
     *
     * <p>O teto do Caffeine é um <em>limite eventual</em>, não um invariante
     * instantâneo: a inserção é rápida porque o despejo é agendado num executor,
     * e não feito na chamada. Inserir 500 chaves num cache de teto 10 e ler o
     * tamanho na linha seguinte devolve algo perto de 500 — o que não é defeito
     * do cache, é o contrato dele.
     *
     * <p>Isso importa para o caminho quente: trocar o executor por um que roda na
     * thread chamadora faria o despejo ser pago por uma tecla digitada. Então o
     * executor fica como está e o teste ganha esta costura, em vez de o contrário.
     */
    void manutencao() {
        cache.cleanUp();
    }

    /**
     * O que o cache fez, para a configuração publicar como métrica.
     *
     * <p>É um record do adaptador e não um {@code MeterRegistry} injetado aqui: o
     * decorador não conhece Micrometer, e a configuração não conhece Caffeine.
     * Cada um fica com a dependência que já tinha.
     */
    public Estatisticas estatisticas() {
        var stats = cache.stats();
        return new Estatisticas(stats.hitCount(), stats.missCount(), cache.estimatedSize());
    }

    /**
     * Acertos, erros e tamanho.
     *
     * <p>A taxa de acerto é o número que diz se o recurso está fazendo algo. Alta
     * é o esperado: a distribuição é uma pessoa digitando uma palavra. Baixa
     * significa que o TTL é curto demais para a velocidade real de digitação, ou
     * que o teto de chaves está despejando antes de o TTL vencer — e as duas têm
     * conserto diferente, que é por que os três números saem separados.
     */
    public record Estatisticas(long acertos, long erros, long itens) {

        public double taxaDeAcerto() {
            long total = acertos + erros;
            return total == 0 ? 0.0 : (double) acertos / total;
        }
    }
}
