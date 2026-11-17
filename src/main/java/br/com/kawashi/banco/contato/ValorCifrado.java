package br.com.kawashi.banco.contato;

import org.springframework.stereotype.Component;

import br.com.kawashi.banco.compartilhado.cifra.CifraDeDados;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Faz o valor do contato (e-mail ou telefone) ir cifrado para a coluna VALOR_CIFRADO e voltar em
 * claro para a entidade.
 */
@Component
@Converter
public class ValorCifrado implements AttributeConverter<String, String> {

    public static final String CONTEXTO = "CONTATO.VALOR";

    private final CifraDeDados cifra;

    public ValorCifrado(CifraDeDados cifra) {
        this.cifra = cifra;
    }

    @Override
    public String convertToDatabaseColumn(String valor) {
        return cifra.cifrar(CONTEXTO, valor);
    }

    @Override
    public String convertToEntityAttribute(String cifrado) {
        return cifra.decifrar(CONTEXTO, cifrado);
    }
}
