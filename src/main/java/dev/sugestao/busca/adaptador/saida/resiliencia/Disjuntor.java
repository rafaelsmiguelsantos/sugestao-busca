package dev.sugestao.busca.adaptador.saida.resiliencia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Um disjuntor. Pára de bater numa dependência que já respondeu que não está lá.
 *
 * <p><strong>Por que existe, se já há prazo por dependência.</strong> As duas
 * coisas protegem coisas diferentes, e é comum confundi-las. O prazo protege a
 * <em>requisição</em>: 18 ms e desiste, para a pessoa que digita não esperar. O
 * disjuntor protege a <em>dependência e o processo</em>: com o Solr fora do ar,
 * cada tecla ainda abre uma conexão TCP e espera o prazo vencer. Numa caixa de
 * busca isso são dezenas de tentativas por segundo contra um host morto, cada
 * uma segurando um descritor de arquivo e uma thread, e nenhuma delas com a
 * menor chance de responder. O prazo faz a falha ser rápida; o disjuntor faz a
 * falha ser <em>barata</em>.
 *
 * <p>E há o outro lado: quando o Solr volta, ele volta frio. Receber de uma vez
 * toda a carga que estava sendo rejeitada é a forma mais comum de derrubá-lo
 * outra vez. O meio-aberto existe para isso — algumas sondas primeiro, carga
 * inteira depois.
 *
 * <p><strong>Três estados.</strong>
 * <ul>
 *   <li>{@code FECHADO} — passa. Conta as falhas numa janela das últimas
 *       chamadas.</li>
 *   <li>{@code ABERTO} — não passa, e não custa nada: a recusa é uma leitura de
 *       campo volátil, sem trava e sem rede.</li>
 *   <li>{@code MEIO_ABERTO} — passa um número pequeno de sondas. Sonda que dá
 *       certo fecha; sonda que falha reabre na hora, sem esperar a janela.</li>
 * </ul>
 *
 * <p><strong>Janela, e não falhas consecutivas.</strong> Contar consecutivas é
 * mais simples e não abre no caso que mais importa: uma dependência que responde
 * metade das vezes nunca acumula a sequência, e metade das pessoas fica sem
 * lista. A janela mede taxa, e {@code minimoDeChamadas} impede que uma falha
 * isolada num serviço recém-subido abra o disjuntor com amostra de tamanho um.
 *
 * <p><strong>Timeout conta como falha, e isso é de propósito.</strong> O prazo
 * do cliente HTTP vira exceção, e exceção entra na janela. Numa camada de
 * sugestão, lento e morto são a mesma coisa: uma lista que chega depois da
 * próxima tecla não serve para nada.
 *
 * <p>Não conhece Spring, Solr nem HTTP — recebe um {@link Supplier} e devolve o
 * que ele devolver. É testável sem subir nada, e é por isso que a decisão de
 * quando abrir pode ser discutida lendo um teste em vez de um incidente.
 */
public final class Disjuntor {

    private static final Logger log = LoggerFactory.getLogger(Disjuntor.class);

    public enum Estado { FECHADO, ABERTO, MEIO_ABERTO }

    private final String nome;
    private final int janela;
    private final int minimoDeChamadas;
    private final int limiteDeFalhas;
    private final long esperaNanos;
    private final int sondas;

    /**
     * A janela, como anel de booleanos. Um array de tamanho fixo em vez de uma
     * fila porque este código roda em toda consulta: alocação por chamada numa
     * camada que existe para ser barata seria uma piada de mau gosto.
     */
    private final boolean[] falhou;
    private int proximo;
    private int amostras;
    private int falhasNaJanela;

    /**
     * Voláteis para o caminho rápido. Quando o disjuntor está aberto — que é
     * justamente quando o volume de chamadas rejeitadas é alto — a recusa lê
     * estes dois campos e volta, sem tocar na trava.
     */
    private volatile Estado estado = Estado.FECHADO;
    private volatile long abertoEm;

    private final AtomicInteger sondasRestantes = new AtomicInteger();
    private final ReentrantLock trava = new ReentrantLock();

    public Disjuntor(String nome, int janela, int minimoDeChamadas,
                     int limiteDeFalhas, Duration espera, int sondas) {

        if (janela < 1) {
            throw new IllegalArgumentException("janela precisa ser ao menos 1");
        }
        if (limiteDeFalhas < 1 || limiteDeFalhas > janela) {
            throw new IllegalArgumentException(
                    "limite de falhas precisa estar entre 1 e o tamanho da janela");
        }
        if (minimoDeChamadas < limiteDeFalhas) {
            // Minimo abaixo do limite nunca segura nada: com minimo 2 e limite
            // 10, as 10 falhas ja implicam 10 chamadas. Deixar passar seria
            // aceitar uma configuracao que mente sobre o que faz.
            throw new IllegalArgumentException(
                    "minimo de chamadas abaixo do limite de falhas nao protege de nada");
        }
        if (sondas < 1) {
            throw new IllegalArgumentException("precisa de ao menos uma sonda para voltar");
        }

        this.nome = nome;
        this.janela = janela;
        this.minimoDeChamadas = minimoDeChamadas;
        this.limiteDeFalhas = limiteDeFalhas;
        this.esperaNanos = espera.toNanos();
        this.sondas = sondas;
        this.falhou = new boolean[janela];
    }

    /**
     * Executa a chamada, ou recusa sem executar.
     *
     * @throws DisjuntorAberto quando a chamada nem foi tentada
     */
    public <T> T executar(Supplier<T> chamada) {
        long faltam = quantoFaltaParaSondar();
        if (faltam > 0) {
            throw new DisjuntorAberto(nome, faltam / 1_000_000);
        }

        try {
            T resultado = chamada.get();
            registrarSucesso();
            return resultado;
        } catch (RuntimeException e) {
            registrarFalha();
            throw e;
        }
    }

    public Estado estado() {
        return estado;
    }

    /**
     * Zero quando a chamada pode seguir; o que falta em nanos quando não pode.
     *
     * <p>O caminho rápido é o primeiro {@code if}: estado fechado sai daqui sem
     * trava nenhuma, e é o caso de 99,9% da vida do serviço.
     */
    private long quantoFaltaParaSondar() {
        Estado atual = estado;

        if (atual == Estado.FECHADO) {
            return 0;
        }

        if (atual == Estado.ABERTO) {
            long decorrido = System.nanoTime() - abertoEm;
            if (decorrido < esperaNanos) {
                return esperaNanos - decorrido;
            }
            // Passou a espera: vira meio-aberto e esta chamada é a primeira
            // sonda. Sob corrida, duas threads podem chegar aqui juntas; a
            // trava resolve e só uma faz a transição.
            trava.lock();
            try {
                if (estado == Estado.ABERTO && System.nanoTime() - abertoEm >= esperaNanos) {
                    estado = Estado.MEIO_ABERTO;
                    sondasRestantes.set(sondas);
                    log.info("disjuntor '{}' ABERTO -> MEIO_ABERTO: {} sonda(s) liberada(s)",
                            nome, sondas);
                }
            } finally {
                trava.unlock();
            }
        }

        // Meio-aberto: só as sondas passam. As demais são recusadas como se
        // estivesse aberto, porque é o que elas são — tráfego que não pode
        // atropelar uma dependência que acabou de voltar.
        //
        // getAndUpdate com piso em zero, e não getAndDecrement: o decremento
        // puro continuaria descendo a cada chamada recusada, e numa caixa de
        // busca sob disjuntor aberto isso são milhares de decrementos por
        // minuto até estourar o inteiro por baixo. O comportamento não mudaria
        // no caminho feliz, o que é o que torna o bug divertido de achar.
        int antes = sondasRestantes.getAndUpdate(n -> n > 0 ? n - 1 : 0);
        return antes > 0 ? 0 : esperaNanos;
    }

    private void registrarSucesso() {
        if (estado == Estado.FECHADO) {
            anotar(false);
            return;
        }

        trava.lock();
        try {
            if (estado == Estado.MEIO_ABERTO) {
                estado = Estado.FECHADO;
                zerarJanela();
                log.info("disjuntor '{}' MEIO_ABERTO -> FECHADO: a dependencia voltou", nome);
            }
        } finally {
            trava.unlock();
        }
    }

    private void registrarFalha() {
        if (estado == Estado.MEIO_ABERTO) {
            trava.lock();
            try {
                if (estado == Estado.MEIO_ABERTO) {
                    // Reabre na hora, sem esperar a janela encher. A sonda era a
                    // pergunta "ja voltou?", e a resposta foi não.
                    abrir("a sonda falhou");
                }
            } finally {
                trava.unlock();
            }
            return;
        }
        anotar(true);
    }

    /** Escreve o resultado na janela e decide se abre. */
    private void anotar(boolean falha) {
        trava.lock();
        try {
            if (estado != Estado.FECHADO) {
                return;
            }

            if (falhou[proximo]) {
                falhasNaJanela--;
            }
            falhou[proximo] = falha;
            if (falha) {
                falhasNaJanela++;
            }

            proximo = (proximo + 1) % janela;
            if (amostras < janela) {
                amostras++;
            }

            if (amostras >= minimoDeChamadas && falhasNaJanela >= limiteDeFalhas) {
                abrir(falhasNaJanela + " falhas nas ultimas " + amostras + " chamadas");
            }
        } finally {
            trava.unlock();
        }
    }

    /** Sempre chamado com a trava em mãos. */
    private void abrir(String motivo) {
        estado = Estado.ABERTO;
        abertoEm = System.nanoTime();
        zerarJanela();
        log.warn("disjuntor '{}' ABERTO ({}): parando de chamar por {} ms",
                nome, motivo, esperaNanos / 1_000_000);
    }

    private void zerarJanela() {
        Arrays.fill(falhou, false);
        proximo = 0;
        amostras = 0;
        falhasNaJanela = 0;
    }
}
