package br.com.kawashi.banco.compartilhado.erros;

/** Um parametro da requisicao (nao do corpo) tem valor fora do aceito. Vira HTTP 400. */
public class ParametroInvalidoException extends RuntimeException {

    public ParametroInvalidoException(String mensagem) {
        super(mensagem);
    }
}
