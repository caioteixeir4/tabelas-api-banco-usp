package br.com.kawashi.banco.cliente;

import br.com.kawashi.banco.compartilhado.Cnpj;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de criacao e de atualizacao. O CNPJ aceita pontuacao ("12.345.678/0001-90") e e guardado so
 * com digitos. Na atualizacao ele precisa ser o mesmo do cadastro: CNPJ nao muda.
 */
public record ClienteRequest(
        @NotBlank(message = "O CNPJ é obrigatório")
        @Cnpj
        String cnpj,

        @NotBlank(message = "A razão social é obrigatória")
        @Size(max = 200, message = "A razão social aceita no máximo 200 caracteres")
        String razaoSocial,

        @Size(max = 200, message = "O nome fantasia aceita no máximo 200 caracteres")
        String nomeFantasia) {
}
