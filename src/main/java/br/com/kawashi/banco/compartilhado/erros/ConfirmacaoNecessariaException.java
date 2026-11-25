package br.com.kawashi.banco.compartilhado.erros;

/**
 * Operacao sensivel e a passkey nao foi usada ha pouco nesta sessao: 403 com
 * {@code "confirmar": true}. O site pede a passkey (/api/auth/confirmar) e repete o pedido.
 */
public class ConfirmacaoNecessariaException extends RuntimeException {

    public ConfirmacaoNecessariaException() {
        super("Esta operação pede que você confirme com a sua passkey.");
    }
}
