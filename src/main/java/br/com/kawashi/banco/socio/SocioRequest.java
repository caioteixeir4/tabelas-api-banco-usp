package br.com.kawashi.banco.socio;

import java.math.BigDecimal;

import br.com.kawashi.banco.compartilhado.Cpf;
import br.com.kawashi.banco.compartilhado.Textos;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corpo de criacao e de atualizacao. O CPF aceita pontuacao ("123.456.789-09") e e guardado so com
 * digitos; na atualizacao ele precisa ser o mesmo do cadastro: CPF nao muda. A participacao e um
 * percentual de 0,01 a 100,00 com no maximo 2 casas decimais. O limite de 100% na SOMA dos socios da
 * empresa e regra do servico.
 */
public record SocioRequest(
        @NotBlank(message = "O nome é obrigatório")
        @Size(max = 150, message = "O nome aceita no máximo 150 caracteres")
        String nome,

        @NotBlank(message = "O CPF é obrigatório")
        @Size(max = 30, message = "O CPF aceita no máximo 30 caracteres")
        @Cpf
        String cpf,

        @NotNull(message = "A participação é obrigatória")
        @DecimalMin(value = "0.01", message = "A participação deve ser maior que zero (mínimo 0.01)")
        @DecimalMax(value = "100.00", message = "A participação deve ser no máximo 100")
        @Digits(integer = 3, fraction = 2, message = "A participação aceita no máximo 2 casas decimais")
        BigDecimal participacao) {

    public SocioRequest {
        nome = Textos.aparar(nome);
        cpf = Textos.aparar(cpf);
        // O JSON preserva a escala escrita: "100.000" chega com 3 casas e o @Digits recusaria zeros a
        // direita que nao mudam o valor (quem manda 33.330 quer dizer 33.33). Tirar os zeros antes de
        // validar aceita esses casos e continua recusando 0.001 de verdade.
        if (participacao != null && participacao.scale() > 2) {
            participacao = participacao.stripTrailingZeros();
        }
    }
}
