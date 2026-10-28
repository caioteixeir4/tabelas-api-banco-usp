package br.com.kawashi.banco.compartilhado;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Arrays;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** As 27 unidades da federacao. O banco so confere "duas letras maiusculas"; a lista oficial e daqui. */
public enum Uf {
    AC, AL, AP, AM, BA, CE, DF, ES, GO, MA, MT, MS, MG, PA, PB, PR, PE, PI, RJ, RN, RS, RO, RR, SC, SP, SE, TO;

    public static boolean existe(String sigla) {
        return sigla != null && Arrays.stream(values()).anyMatch(uf -> uf.name().equals(sigla));
    }

    /** Sigla de UF existente (maiuscula). Nulo e considerado valido (use com @NotBlank se obrigatorio). */
    @Documented
    @Constraint(validatedBy = Valida.Validador.class)
    @Target({FIELD, PARAMETER, RECORD_COMPONENT})
    @Retention(RUNTIME)
    public @interface Valida {

        String message() default "UF inválida (use a sigla de um estado brasileiro, ex.: SP)";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};

        class Validador implements ConstraintValidator<Valida, String> {
            @Override
            public boolean isValid(String valor, ConstraintValidatorContext contexto) {
                return valor == null || valor.isBlank() || Uf.existe(valor.trim().toUpperCase());
            }
        }
    }
}
