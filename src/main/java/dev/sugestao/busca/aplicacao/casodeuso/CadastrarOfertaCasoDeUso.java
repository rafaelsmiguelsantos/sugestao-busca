package dev.sugestao.busca.aplicacao.casodeuso;

import dev.sugestao.busca.aplicacao.porta.entrada.Autorizacao;
import dev.sugestao.busca.aplicacao.porta.entrada.CadastrarOferta;
import dev.sugestao.busca.aplicacao.porta.entrada.ComandoDeCadastroDeOferta;
import dev.sugestao.busca.aplicacao.porta.entrada.JornadaNaoAutorizada;
import dev.sugestao.busca.aplicacao.porta.entrada.ResultadoDoCadastro;
import dev.sugestao.busca.aplicacao.porta.saida.RegistroDeOfertas;
import dev.sugestao.busca.dominio.oferta.Oferta;

import java.util.Optional;

/**
 * Transforma pedido em oferta, confere se já existia, grava.
 *
 * <p>É curto, e é curto porque a regra está onde deveria: a validação inteira
 * mora em {@link Oferta#de}, no domínio, e este caso de uso não repete nenhuma
 * delas. Se amanhã o cadastro passar a aceitar outra entrada — uma fila, um
 * arquivo em lote, um console administrativo — ela chama o mesmo
 * {@link ComandoDeCadastroDeOferta} e herda as mesmas regras sem copiar uma
 * linha.
 *
 * <p>Não há transação, e isso é um limite conhecido desta implementação. A
 * leitura de existência e a gravação são duas chamadas ao índice, e dois
 * cadastros simultâneos do mesmo identificador podem ambos reportar
 * {@code criada}. A gravação continua correta — ela é idempotente e a última
 * vence —, só o status fica otimista. Num registro de ofertas de verdade isso é
 * um {@code INSERT ... ON CONFLICT} e o problema não existe; aqui a troca é
 * deliberada, porque o alternativo seria um bloqueio distribuído para acertar um
 * código de status.
 */
public class CadastrarOfertaCasoDeUso implements CadastrarOferta {

    private final RegistroDeOfertas registro;

    public CadastrarOfertaCasoDeUso(RegistroDeOfertas registro) {
        this.registro = registro;
    }

    @Override
    public ResultadoDoCadastro executar(ComandoDeCadastroDeOferta comando, Autorizacao autorizacao) {
        // Valida antes de tocar o registro. Uma oferta recusada não deve ter
        // custado uma ida ao índice, e sobretudo não deve ter sobrescrito nada.
        Oferta oferta = comando.paraOferta();

        // Autorização DEPOIS da validação, e a ordem foi escolhida.
        //
        // Autorizar primeiro pareceria mais seguro e diria menos: o chamador
        // receberia 403 para um corpo que também estava malformado, corrigiria o
        // token, reenviaria, e só então descobriria os oito campos errados. E
        // não há risco em validar primeiro — a validação é pura, não toca o
        // registro e não revela nada sobre ofertas existentes.
        //
        // A jornada comparada é a NORMALIZADA pelo domínio, não a que veio no
        // corpo. Sem isso, " Credito " passaria pela comparação com um token de
        // "credito" ou não, dependendo de espaço em branco.
        if (!autorizacao.podeCadastrarEm(oferta.jornada())) {
            throw new JornadaNaoAutorizada(oferta.jornada(), autorizacao.jornada());
        }

        boolean existia = registro.porId(oferta.ofertaId()).isPresent();
        registro.gravar(oferta);

        return new ResultadoDoCadastro(oferta, !existia);
    }

    @Override
    public Optional<Oferta> porId(String ofertaId) {
        if (ofertaId == null || ofertaId.isBlank()) {
            return Optional.empty();
        }
        return registro.porId(ofertaId.strip().toUpperCase());
    }
}
