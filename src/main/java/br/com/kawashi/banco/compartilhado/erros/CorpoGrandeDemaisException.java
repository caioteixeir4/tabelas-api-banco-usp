package br.com.kawashi.banco.compartilhado.erros;

import java.io.IOException;

/**
 * O corpo da requisicao passou do limite DURANTE a leitura (corpo sem Content-Length, em
 * transferencia fragmentada). Vira HTTP 413. E uma IOException porque nasce dentro da leitura do
 * fluxo; o TratadorDeErros a reconhece na cadeia de causas do erro de leitura do JSON.
 */
public class CorpoGrandeDemaisException extends IOException {

    public CorpoGrandeDemaisException(long limiteEmBytes) {
        super(mensagem(limiteEmBytes));
    }

    public static String mensagem(long limiteEmBytes) {
        return "O corpo da requisição passa do limite de " + limiteEmBytes + " bytes.";
    }
}
