package br.com.kawashi.banco.filial;

import br.com.kawashi.banco.compartilhado.Cnpj;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de criacao e de atualizacao. O CNPJ aceita pontuacao ("12.345.678/0002-71") e e guardado so
 * com digitos. Na atualizacao ele precisa ser o mesmo do cadastro: so o nome muda.
 */
public record FilialRequest(
        @NotBlank(message = "O CNPJ é obrigatório")
        @Cnpj
        String cnpj,

        @NotBlank(message = "O nome é obrigatório")
        @Size(max = 150, message = "O nome aceita no máximo 150 caracteres")
        String nome) {
}
