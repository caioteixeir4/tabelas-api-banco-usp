package br.com.kawashi.banco.pessoa;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import br.com.kawashi.banco.compartilhado.Documentos;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * O documento precisa combinar com a natureza: CPF valido para "F", CNPJ valido para "J". E uma
 * validacao do pedido inteiro (olha dois campos), mas o erro e apontado no campo "documento".
 */
@Documented
@Constraint(validatedBy = DocumentoDaPessoa.Validador.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface DocumentoDaPessoa {

    String message() default "Documento inválido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validador implements ConstraintValidator<DocumentoDaPessoa, PessoaRequest> {
        @Override
        public boolean isValid(PessoaRequest pedido, ConstraintValidatorContext contexto) {
            String natureza = pedido.natureza();
            String documento = pedido.documento();
            // Natureza ausente ou fora de F/J e documento em branco ja sao erros dos proprios campos.
            if (documento == null || documento.trim().isEmpty() || natureza == null) {
                return true;
            }
            // Documento mascarado (como a API devolve) nao tem digitos para conferir. Quem decide se
            // ele vale e o servico: recusa no cadastro e aceita na atualizacao se for a mascara do
            // documento da propria pessoa.
            if (br.com.kawashi.banco.compartilhado.cifra.Mascaras.eMascara(documento)) {
                return true;
            }
            boolean valido;
            String mensagem;
            if (natureza.equals("F")) {
                valido = Documentos.cpfValido(documento);
                mensagem = "CPF inválido";
            } else if (natureza.equals("J")) {
                valido = Documentos.cnpjValido(documento);
                mensagem = "CNPJ inválido";
            } else {
                return true;
            }
            if (!valido) {
                contexto.disableDefaultConstraintViolation();
                contexto.buildConstraintViolationWithTemplate(mensagem).addPropertyNode("documento").addConstraintViolation();
            }
            return valido;
        }
    }
}
