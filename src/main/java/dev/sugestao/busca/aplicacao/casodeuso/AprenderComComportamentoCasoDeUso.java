package dev.sugestao.busca.aplicacao.casodeuso;

import dev.sugestao.busca.aplicacao.ParametrosDeAprendizado;
import dev.sugestao.busca.aplicacao.porta.entrada.AprenderComComportamento;
import dev.sugestao.busca.aplicacao.porta.entrada.ResultadoDoAprendizado;
import dev.sugestao.busca.aplicacao.porta.saida.CatalogoCurado;
import dev.sugestao.busca.aplicacao.porta.saida.EscritorDePerfil;
import dev.sugestao.busca.aplicacao.porta.saida.FilaDeEventos;
import dev.sugestao.busca.aplicacao.porta.saida.PublicadorDeVocabulario;
import dev.sugestao.busca.aplicacao.porta.saida.RepositorioDeAgregados;
import dev.sugestao.busca.dominio.agregado.ClassificadorDeJornada;
import dev.sugestao.busca.dominio.agregado.TermoAgregado;

import java.util.ArrayList;
import java.util.List;

/**
 * O caminho de escrita inteiro, fora da requisição: consome, agrega, saneia por
 * k-anonimato, publica.
 *
 * <p>É aqui que a pergunta "como isso aprende" tem resposta concreta. O que a
 * pessoa digitou volta para ela pelo perfil, em 24 h; o que muita gente digitou
 * vira vocabulário para todos em 30 d, e só depois de provar que veio de gente
 * suficiente. Os dois caminhos saem do mesmo evento e nunca se cruzam.
 *
 * <p>A ordem entre publicar e atualizar perfil não é indiferente. O vocabulário
 * vai primeiro porque é ele que o perfil referencia: um histórico apontando para
 * um termo que ainda não existe no índice dá lista com buraco, enquanto um termo
 * publicado que nenhum perfil cita ainda é apenas um termo que ninguém viu.
 */
public class AprenderComComportamentoCasoDeUso implements AprenderComComportamento {

    private static final int EXEMPLOS_NO_RELATORIO = 5;

    private final FilaDeEventos fila;
    private final RepositorioDeAgregados agregados;
    private final CatalogoCurado catalogoCurado;
    private final PublicadorDeVocabulario publicador;
    private final EscritorDePerfil perfis;
    private final ParametrosDeAprendizado parametros;

    public AprenderComComportamentoCasoDeUso(
            FilaDeEventos fila,
            RepositorioDeAgregados agregados,
            CatalogoCurado catalogoCurado,
            PublicadorDeVocabulario publicador,
            EscritorDePerfil perfis,
            ParametrosDeAprendizado parametros) {
        this.fila = fila;
        this.agregados = agregados;
        this.catalogoCurado = catalogoCurado;
        this.publicador = publicador;
        this.perfis = perfis;
        this.parametros = parametros;
    }

    @Override
    public ResultadoDoAprendizado executar() {
        var lote = fila.consumir(parametros.loteDeConsumo());
        agregados.acumular(lote);

        // O perfil é atualizado ANTES do caminho de vocabulário, e a ordem é
        // uma correção.
        //
        // Atualizar perfil não precisa de Solr: histórico e afinidade saem do
        // agregado em memória. Mas a classificação de jornada precisa — ela lê o
        // catálogo curado do índice — e ela estava antes daqui. Com o Solr fora,
        // a leitura do catálogo estourava e levava consigo a atualização de
        // perfil, que teria funcionado sozinha. Um caminho que não depende da
        // dependência passou a depender dela por acidente de ordenação.
        //
        // Agora a rodada entrega o que consegue: perfil primeiro, vocabulário
        // depois. Se o índice estiver fora, os termos ficam no agregado e a
        // próxima rodada publica — o agendador é o mecanismo de nova tentativa.
        var comportamentos = agregados.comportamentos(
                parametros.janelaDoComportamento(),
                parametros.tamanhoDoHistorico(),
                parametros.afinidade());
        perfis.atualizar(comportamentos);

        var termos = agregados.termos(parametros.janelaDoVocabulario());

        // A régua é lida uma vez por rodada, não por termo. Classificar aqui, e
        // não na leitura, é o que permite ao ranking por jornada e ao ranking
        // por comportamento alcançarem a camada popular — sem isso os dois
        // valem só para as linhas de catálogo, que são a minoria da lista.
        var classificador = new ClassificadorDeJornada(catalogoCurado.itens());

        var publicaveis = new ArrayList<TermoAgregado>(termos.size());
        var retidos = new ArrayList<String>();

        for (TermoAgregado termo : termos) {
            if (termo.acimaDoPiso(parametros.kAnonimatoMinimo())) {
                publicaveis.add(termo.classificadoComo(classificador.classificar(termo.termo())));
            } else {
                retidos.add(termo.termo());
            }
        }

        int publicados = publicaveis.isEmpty() ? 0 : publicador.publicar(publicaveis);

        return new ResultadoDoAprendizado(
                lote.size(),
                termos.size(),
                publicados,
                retidos.size(),
                comportamentos.size(),
                // Só o relatório operacional vê termo retido, e só alguns. Eles
                // são, por definição, os que vieram de gente de menos — é
                // exatamente o conjunto que não deve circular.
                retidos.stream().limit(EXEMPLOS_NO_RELATORIO).toList());
    }
}
