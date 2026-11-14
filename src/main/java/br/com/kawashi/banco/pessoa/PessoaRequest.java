package br.com.kawashi.banco.pessoa;

import java.math.BigDecimal;
import java.util.Locale;

import br.com.kawashi.banco.compartilhado.Textos;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo de criacao e de atualizacao. O documento aceita pontuacao e e guardado so com digitos. Na
 * atualizacao, tipo, natureza e documento precisam ser os mesmos do cadastro: nao mudam. A
 * participacao (0,01 a 100,00, ate 2 casas) so existe para SOCIO e o cargo so para COLABORADOR:
 * essas duas regras, e a soma das participacoes, sao do servico.
 */
@DocumentoDaPessoa
public record PessoaRequest(
        @NotBlank(message = "O tipo é obrigatório")
        @Pattern(regexp = "CLIENTE|COLABORADOR|SOCIO|FORNECEDOR",
                message = "Tipo inválido (use CLIENTE, COLABORADOR, SOCIO ou FORNECEDOR)")
        String tipo,

        @NotBlank(message = "A natureza é obrigatória")
        @Pattern(regexp = "F|J", message = "Natureza inválida (use F para pessoa física ou J para jurídica)")
        String natureza,

        @NotBlank(message = "O documento é obrigatório")
        @Size(max = 30, message = "O documento aceita no máximo 30 caracteres")
        String documento,

        @NotBlank(message = "O nome é obrigatório")
        @Size(max = 200, message = "O nome aceita no máximo 200 caracteres")
        String nome,

        @DecimalMin(value = "0.01", message = "A participação deve ser maior que zero (mínimo 0.01)")
        @DecimalMax(value = "100.00", message = "A participação deve ser no máximo 100")
        @Digits(integer = 3, fraction = 2, message = "A participação aceita no máximo 2 casas decimais")
        BigDecimal participacao,

        @Size(max = 100, message = "O cargo aceita no máximo 100 caracteres")
        String cargo) {

    public PessoaRequest {
        tipo = maiuscula(tipo);
        natureza = maiuscula(natureza);
        documento = Textos.aparar(documento);
        nome = Textos.aparar(nome);
        cargo = Textos.aparar(cargo);
        // O JSON preserva a escala escrita: "100.000" chega com 3 casas e o @Digits recusaria zeros a
        // direita que nao mudam o valor (quem manda 33.330 quer dizer 33.33). Tirar os zeros antes de
        // validar aceita esses casos e continua recusando 0.001 de verdade.
        if (participacao != null && participacao.scale() > 2) {
            participacao = participacao.stripTrailingZeros();
        }
    }

    private static String maiuscula(String texto) {
        return texto == null ? null : texto.strip().toUpperCase(Locale.ROOT);
    }
}
