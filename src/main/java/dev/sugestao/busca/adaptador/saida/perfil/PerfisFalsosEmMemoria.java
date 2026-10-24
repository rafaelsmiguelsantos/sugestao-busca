package dev.sugestao.busca.adaptador.saida.perfil;

import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDePerfil;
import dev.sugestao.busca.dominio.Perfil;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O lugar do DynamoDB enquanto o POC roda nesta máquina. Perfis falsos,
 * inventados para mostrar o mecanismo — nenhum cliente real por trás de nenhum
 * destes números.
 *
 * <p>Eles existem para a demonstração do desenho: digitar as mesmas letras com
 * perfis diferentes e ver a lista mudar. Por isso são poucos e bem separados —
 * cada um exercita uma parte distinta da peneira e da cota.
 *
 * <p>Trocar isto por DynamoDB é escrever outra classe que implementa a mesma
 * porta. Nada acima desta linha precisa saber que a troca aconteceu.
 */
public class PerfisFalsosEmMemoria implements RepositorioDePerfil {

    private static final Map<String, Perfil> PERFIS = Map.of(

            // Chega sem história. Serve de linha de base: é a lista popular do
            // segmento, igual à que um cliente novo veria.
            "pf-novo", new Perfil(
                    List.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    true),

            // Pix recusado por saldo há duas horas. É o perfil que mostra a
            // linha de evento abrindo a lista com rótulo próprio.
            "pf-pix-recusado", new Perfil(
                    List.of("pagar boleto", "limite do pix", "pix agendado"),
                    Set.of("pix_recusado_72h", "saldo_insuficiente_24h"),
                    Set.of(),
                    Set.of("ANTECIPACAO", "EMPRESTIMO", "LIMITE_CARTAO"),
                    true),

            // Já tem CDB. Digita "cdb" e não recebe de volta o que já comprou —
            // é o corte de produto contratado, que vem do perfil e não do fq.
            "pf-investidor", new Perfil(
                    List.of("tesouro direto", "rendimento da poupanca", "cdb"),
                    Set.of(),
                    Set.of("CDB"),
                    Set.of("TESOURO", "LCI"),
                    true),

            // Elegibilidade de nada. Mostra a falha fechada funcionando: as
            // ofertas que exigem elegibilidade somem, e só elas.
            "pf-sem-elegibilidade", new Perfil(
                    List.of("aumentar limite do cartao"),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    true),

            // Lojista com recebível disponível. O mesmo mecanismo no outro
            // segmento — e a prova de que o segmento do token muda a lista
            // antes de qualquer personalização.
            "pj-lojista", new Perfil(
                    List.of("emitir boleto", "taxa da maquininha"),
                    Set.of("recebivel_disponivel_24h"),
                    Set.of(),
                    Set.of("ANTECIPACAO_PJ", "CAPITAL_GIRO"),
                    true));

    @Override
    public Perfil porCliente(String clienteId) {
        // Cliente sem perfil montado não é erro: é cliente novo. O NEUTRO já
        // diz exatamente isso, e `conhecido = false` deixa a lista marcada
        // como degradada para quem for registrar a impressão.
        return PERFIS.getOrDefault(clienteId, Perfil.NEUTRO);
    }
}
