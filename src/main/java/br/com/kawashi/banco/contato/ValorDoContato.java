package br.com.kawashi.banco.contato;

import static java.lang.annotation.ElementType.TYPE;
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
 * O valor precisa combinar com o tipo: um e-mail para EMAIL; um telefone brasileiro com DDD (10 ou
 * 11 digitos, pontuacao comum aceita) para TELEFONE. Olha dois campos, mas aponta o erro em "valor".
 */
@Documented
@Constraint(validatedBy = ValorDoContato.Validador.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface ValorDoContato {

    String message() default "Valor inválido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validador implements ConstraintValidator<ValorDoContato, ContatoRequest> {

        /** Algo, arroba, algo, ponto, algo; sem espacos. A prova real de um e-mail e ele receber mensagem. */
        private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s.]+");
        private static final Pattern TELEFONE = Pattern.compile("[0-9\\s().\\-]+");

        @Override
        public boolean isValid(ContatoRequest pedido, ConstraintValidatorContext contexto) {
            String valor = pedido.valor();
            // Tipo ausente ou invalido e valor em branco ja sao erros dos proprios campos.
            if (valor == null || valor.isEmpty() || pedido.tipo() == null) {
                return true;
            }
            String mensagem;
            if (pedido.tipo().equals("EMAIL")) {
                if (EMAIL.matcher(valor).matches()) {
                    return true;
                }
                mensagem = "E-mail inválido";
            } else if (pedido.tipo().equals("TELEFONE")) {
                int digitos = Documentos.somenteDigitos(valor).length();
                if (TELEFONE.matcher(valor).matches() && (digitos == 10 || digitos == 11)) {
                    return true;
                }
                mensagem = "Telefone inválido: informe o DDD e o número com 10 ou 11 dígitos";
            } else {
                return true;
            }
            contexto.disableDefaultConstraintViolation();
            contexto.buildConstraintViolationWithTemplate(mensagem).addPropertyNode("valor").addConstraintViolation();
            return false;
        }
    }
}
