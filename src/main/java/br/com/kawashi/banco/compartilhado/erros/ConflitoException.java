package br.com.kawashi.banco.compartilhado.erros;

/**
 * O pedido e valido, mas conflita com o estado atual (duplicidade, registro com dependentes).
 * Vira HTTP 409.
 */
public class ConflitoException extends RuntimeException {

    public ConflitoException(String mensagem) {
        super(mensagem);
    }
}
