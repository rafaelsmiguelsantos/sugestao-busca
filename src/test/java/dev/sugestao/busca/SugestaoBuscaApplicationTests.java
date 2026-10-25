package dev.sugestao.busca;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Confere que o grafo de beans fecha — e, desde que a segurança entrou, que ele
 * fecha <em>com</em> a cadeia de autorização e o decodificador de token.
 *
 * <p>O perfil {@code teste} existe para o segredo. O
 * {@code application.properties} de produção tem o segredo vazio de propósito e
 * a aplicação recusa subir sem ele; sem um segredo aqui, este teste falharia
 * exatamente como falharia um deploy mal configurado. Ver
 * {@code src/test/resources/application-teste.properties}, que também explica
 * por que é um perfil e não um arquivo de mesmo nome.
 */
@SpringBootTest
@ActiveProfiles("teste")
class SugestaoBuscaApplicationTests {

	@Test
	void contextLoads() {
	}

}
