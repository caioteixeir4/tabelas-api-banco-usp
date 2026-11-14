package br.com.kawashi.banco.contato;

import java.util.Locale;

import br.com.kawashi.banco.compartilhado.Textos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de criacao e de atualizacao. O e-mail e guardado em minuscula e o telefone so com digitos
 * (aceita "(11) 98765-4321"). O tipo nao muda na atualizacao. "principal" nulo vale falso (o
 * primeiro contato de cada tipo, porem, e sempre o principal: regra do servico).
 */
@ValorDoContato
public record ContatoRequest(
        @NotBlank(message = "O tipo é obrigatório")
        @Pattern(regexp = "EMAIL|TELEFONE", message = "Tipo inválido (use EMAIL ou TELEFONE)")
        String tipo,

        @NotBlank(message = "O valor é obrigatório")
        @Size(max = 150, message = "O valor aceita no máximo 150 caracteres")
        String valor,

        Boolean principal) {

    public ContatoRequest {
        tipo = tipo == null ? null : tipo.strip().toUpperCase(Locale.ROOT);
        valor = Textos.aparar(valor);
        // O e-mail e guardado em minuscula. Normalizar ANTES da validacao faz o @Size conferir o texto
        // final: em alguns alfabetos a minuscula tem mais caracteres que a maiuscula.
        if (valor != null && "EMAIL".equals(tipo)) {
            valor = valor.toLowerCase(Locale.ROOT);
        }
    }
}
