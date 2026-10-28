package br.com.kawashi.banco.endereco;

import br.com.kawashi.banco.compartilhado.Uf;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de criacao e de atualizacao de endereco. O CEP aceita "12345-678" ou "12345678" e e guardado
 * so com digitos; a UF aceita minuscula e e guardada em maiuscula. "principal" nulo vale falso (o
 * primeiro endereco do cliente, porem, e sempre o principal: regra do servico).
 */
public record EnderecoRequest(
        @NotBlank(message = "O logradouro é obrigatório")
        @Size(max = 200, message = "O logradouro aceita no máximo 200 caracteres")
        String logradouro,

        @Size(max = 20, message = "O número aceita no máximo 20 caracteres")
        String numero,

        @Size(max = 100, message = "O complemento aceita no máximo 100 caracteres")
        String complemento,

        @Size(max = 100, message = "O bairro aceita no máximo 100 caracteres")
        String bairro,

        @NotBlank(message = "A cidade é obrigatória")
        @Size(max = 100, message = "A cidade aceita no máximo 100 caracteres")
        String cidade,

        @NotBlank(message = "A UF é obrigatória")
        @Uf.Valida
        String uf,

        @NotBlank(message = "O CEP é obrigatório")
        @Pattern(regexp = "\\d{5}-?\\d{3}", message = "CEP inválido (use 12345-678 ou 12345678)")
        String cep,

        Boolean principal) {
}
