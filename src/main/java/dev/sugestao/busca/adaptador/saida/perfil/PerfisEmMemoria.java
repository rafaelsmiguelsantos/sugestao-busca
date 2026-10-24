package dev.sugestao.busca.adaptador.saida.perfil;

import dev.sugestao.busca.aplicacao.porta.saida.EscritorDePerfil;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.Perfil;
import dev.sugestao.busca.dominio.SituacaoDaConta;
import dev.sugestao.busca.dominio.agregado.ComportamentoDoCliente;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O lugar do DynamoDB: lê pelo caminho de 30 ms e escreve pelo caminho de
 * aprendizado, que roda fora da requisição.
 *
 * <p>Implementa as duas portas, mas elas continuam separadas — e é essa
 * separação que o compilador verifica. O caso de uso de sugestão recebe só
 * {@link RepositorioDePerfil} e por isso não tem como escrever, mesmo que
 * alguém quisesse. "Nenhuma seta do caminho de leitura escreve" deixa de ser
 * promessa e vira assinatura de construtor.
 *
 * <p>Começa semeado com os perfis falsos da demonstração e vai sendo atualizado
 * pelo que chega da agregação.
 */
public class PerfisEmMemoria implements RepositorioDePerfil, EscritorDePerfil {

    private final Map<String, Perfil> perfis = new ConcurrentHashMap<>();

    public PerfisEmMemoria(Map<String, Perfil> iniciais) {
        perfis.putAll(iniciais);
    }

    @Override
    public Perfil porCliente(String clienteId) {
        // Cliente sem perfil montado não é erro: é cliente novo. O NEUTRO já
        // diz exatamente isso, e `conhecido = false` deixa a lista marcada
        // como degradada para quem for registrar a impressão.
        return perfis.getOrDefault(clienteId, Perfil.NEUTRO);
    }

    @Override
    public void atualizar(List<ComportamentoDoCliente> comportamentos) {
        for (ComportamentoDoCliente comportamento : comportamentos) {
            perfis.merge(
                    comportamento.clienteId(),
                    doZero(comportamento),
                    (atual, novo) -> mesclar(atual, comportamento));
        }
    }

    /**
     * A agregação de busca só sabe de histórico. Gatilho, elegibilidade,
     * produto contratado e situação da conta vêm de outros produtores que este
     * caminho não enxerga — então ele atualiza o que sabe e preserva o resto.
     *
     * <p>Sobrescrever tudo seria o bug silencioso óbvio aqui: uma busca
     * qualquer apagaria a inadimplência do cliente e o crédito novo voltaria a
     * ser oferecido na tecla seguinte.
     */
    private static Perfil mesclar(Perfil atual, ComportamentoDoCliente novo) {
        return new Perfil(
                novo.historico(),
                atual.gatilhosAtivos(),
                atual.produtosContratados(),
                atual.elegibilidades(),
                atual.situacaoDaConta(),
                true);
    }

    private static Perfil doZero(ComportamentoDoCliente comportamento) {
        return new Perfil(
                comportamento.historico(),
                comportamento.gatilhos(),
                Set.of(),
                Set.of(),
                // Cliente visto só pela busca não tem situação de conta
                // conhecida, e o lado seguro disso é DESCONHECIDA: a oferta
                // condicionada não aparece até alguém que saiba dizer.
                SituacaoDaConta.DESCONHECIDA,
                true);
    }
}
