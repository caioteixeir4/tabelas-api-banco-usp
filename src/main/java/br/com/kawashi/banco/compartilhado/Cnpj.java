package br.com.kawashi.banco.compartilhado;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * CNPJ com digitos verificadores corretos. Aceita com ou sem pontuacao. Nulo e considerado valido
 * (use junto com @NotBlank quando o campo for obrigatorio).
 */
@Documented
@Constraint(validatedBy = Cnpj.Validador.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface Cnpj {

    String message() default "CNPJ inválido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validador implements ConstraintValidator<Cnpj, String> {
        @Override
        public boolean isValid(String valor, ConstraintValidatorContext contexto) {
            // Nulo e "em branco comum" (o que o trim() remove) sao assunto do @NotBlank, que faz a mesma
            // conta. Qualquer outro texto, inclusive espaco exotico como U+2003, precisa ser um CNPJ valido:
            // com isBlank() aqui ele passava as duas anotacoes e virava string vazia depois de normalizado.
            return valor == null || valor.trim().isEmpty() || Documentos.cnpjValido(valor);
        }
    }
}
