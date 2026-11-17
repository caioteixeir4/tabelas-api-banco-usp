package br.com.kawashi.banco.pessoa;

import org.springframework.stereotype.Component;

import br.com.kawashi.banco.compartilhado.cifra.CifraDeDados;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Faz o documento da pessoa ir cifrado para a coluna DOCUMENTO_CIFRADO e voltar em claro para a
 * entidade. O resto do codigo continua usando {@code pessoa.getDocumento()} normalmente; so o banco
 * nunca ve o valor.
 */
@Component
@Converter
public class DocumentoCifrado implements AttributeConverter<String, String> {

    public static final String CONTEXTO = "PESSOA.DOCUMENTO";

    private final CifraDeDados cifra;

    public DocumentoCifrado(CifraDeDados cifra) {
        this.cifra = cifra;
    }

    @Override
    public String convertToDatabaseColumn(String documento) {
        return cifra.cifrar(CONTEXTO, documento);
    }

    @Override
    public String convertToEntityAttribute(String cifrado) {
        return cifra.decifrar(CONTEXTO, cifrado);
    }
}
