package dev.sugestao.busca.dominio;

/**
 * Uma linha pronta para a tela. A origem sai junto porque o app precisa dela
 * para registrar a impressão — e é a impressão com posição que torna a camada 4
 * treinável mais tarde.
 */
public record Sugestao(String termo, String rotulo, String destino, Origem origem) {
}
