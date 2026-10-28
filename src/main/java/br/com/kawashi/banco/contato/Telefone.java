package br.com.kawashi.banco.contato;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.regex.Pattern;

import br.com.kawashi.banco.compartilhado.Documentos;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Telefone brasileiro com DDD: 10 digitos (fixo) ou 11 (celular). Aceita pontuacao comum
 * ("(11) 98765-4321", "11 98765-4321", "11.98765.4321"), mas nenhuma letra. Nulo ou em branco e
 * considerado valido (o campo e opcional; o servico decide se o contato precisa dele).
 */
@Documented
@Constraint(validatedBy = Telefone.Validador.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface Telefone {

    String message() default "Telefone inválido: informe o DDD e o número com 10 ou 11 dígitos";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validador implements ConstraintValidator<Telefone, String> {

        private static final Pattern CARACTERES_ACEITOS = Pattern.compile("[0-9\\s().\\-]+");

        @Override
        public boolean isValid(String valor, ConstraintValidatorContext contexto) {
            if (valor == null || valor.isBlank()) {
                return true;
            }
            if (!CARACTERES_ACEITOS.matcher(valor).matches()) {
                return false;
            }
            int digitos = Documentos.somenteDigitos(valor).length();
            return digitos == 10 || digitos == 11;
        }
    }
}
