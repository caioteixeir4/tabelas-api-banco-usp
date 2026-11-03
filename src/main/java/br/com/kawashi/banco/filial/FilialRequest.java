package br.com.kawashi.banco.filial;

import br.com.kawashi.banco.compartilhado.Cnpj;
import br.com.kawashi.banco.compartilhado.Textos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de criacao e de atualizacao. O CNPJ aceita pontuacao ("12.345.678/0002-71") e e guardado so
 * com digitos. Na atualizacao ele precisa ser o mesmo do cadastro: so o nome muda. Os textos chegam
 * aparados ANTES da validacao.
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
