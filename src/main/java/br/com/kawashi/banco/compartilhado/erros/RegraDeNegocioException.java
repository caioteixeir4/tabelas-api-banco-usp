package br.com.kawashi.banco.compartilhado.erros;

/**
 * O pedido esta bem formado, mas viola uma regra de negocio (ex.: filial com raiz de CNPJ diferente
 * da empresa, participacao dos socios acima de 100%). Vira HTTP 422.
 */
public class RegraDeNegocioException extends RuntimeException {

    public RegraDeNegocioException(String mensagem) {
        super(mensagem);
    }
}
