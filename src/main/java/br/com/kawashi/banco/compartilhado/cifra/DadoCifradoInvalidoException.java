package br.com.kawashi.banco.compartilhado.cifra;

/**
 * Um valor cifrado lido do banco nao pode ser decifrado: foi alterado por fora, copiado de outra
 * coluna ou cifrado com outra chave. E erro do servidor (500), nunca do pedido, e a mensagem diz so
 * de qual coluna, sem conteudo.
 */
public class DadoCifradoInvalidoException extends RuntimeException {

    public DadoCifradoInvalidoException(String contexto, String motivo) {
        super("Dado cifrado invalido em " + contexto + ": " + motivo + ".");
    }
}
