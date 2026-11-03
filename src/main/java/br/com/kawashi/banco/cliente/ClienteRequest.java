package br.com.kawashi.banco.cliente;

import br.com.kawashi.banco.compartilhado.Cnpj;
import br.com.kawashi.banco.compartilhado.Textos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de criacao e de atualizacao. O CNPJ aceita pontuacao ("12.345.678/0001-90") e e guardado so
 * com digitos. Na atualizacao ele precisa ser o mesmo do cadastro: CNPJ nao muda. Os textos chegam
 * aparados (espacos das pontas removidos) ANTES da validacao.
 */
public record ClienteRequest(
        @NotBlank(message = "O CNPJ é obrigatório")
        @Size(max = 30, message = "O CNPJ aceita no máximo 30 caracteres")
        @Cnpj
        String cnpj,

        @NotBlank(message = "A razão social é obrigatória")
        @Size(max = 200, message = "A razão social aceita no máximo 200 caracteres")
        String razaoSocial,

        @Size(max = 200, message = "O nome fantasia aceita no máximo 200 caracteres")
        String nomeFantasia) {

    public ClienteRequest {
        cnpj = Textos.aparar(cnpj);
        razaoSocial = Textos.aparar(razaoSocial);
        nomeFantasia = Textos.aparar(nomeFantasia);
    }
}
