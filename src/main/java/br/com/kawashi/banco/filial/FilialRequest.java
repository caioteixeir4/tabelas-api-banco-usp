package br.com.kawashi.banco.filial;

import br.com.kawashi.banco.compartilhado.Cnpj;
import br.com.kawashi.banco.compartilhado.Textos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de criacao e de atualizacao. O CNPJ aceita pontuacao e e guardado so com digitos; precisa
 * ter a mesma raiz da empresa e ordem diferente de 0001. Na atualizacao o CNPJ precisa ser o mesmo:
 * so o nome muda.
 */
public record FilialRequest(
        @NotBlank(message = "O CNPJ é obrigatório")
        @Size(max = 30, message = "O CNPJ aceita no máximo 30 caracteres")
        @Cnpj
        String cnpj,

        @NotBlank(message = "O nome é obrigatório")
        @Size(max = 150, message = "O nome aceita no máximo 150 caracteres")
        String nome) {

    public FilialRequest {
        cnpj = Textos.aparar(cnpj);
        nome = Textos.aparar(nome);
    }
}
