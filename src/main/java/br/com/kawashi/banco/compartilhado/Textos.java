package br.com.kawashi.banco.compartilhado;

/**
 * Normalizacao de texto de entrada. Uma unica definicao de "em branco" para toda a API: a do
 * {@link String#strip()}, que remove espacos Unicode (U+2003, U+3000...) e nao so os caracteres
 * ate U+0020 como o {@code trim()}. Misturar as duas deixava passar texto "so de espacos" pela
 * validacao e gravar vazio (que o Oracle guarda como NULL) no banco.
 */
public final class Textos {

    private Textos() {
    }

    /**
     * Tira os espacos das pontas. Usado nos construtores dos DTOs de entrada, ANTES da validacao,
     * para o {@code @Size} e o {@code @NotBlank} enxergarem o texto final.
     */
    public static String aparar(String texto) {
        return texto == null ? null : texto.strip();
    }

    /** Em um trecho usado com LIKE, faz % e _ valerem como texto (escape com barra invertida). */
    public static String escaparCuringas(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Aparado, e texto opcional em branco vira nulo. */
    public static String limpar(String texto) {
        String aparado = aparar(texto);
        return aparado == null || aparado.isEmpty() ? null : aparado;
    }
}
