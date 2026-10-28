package br.com.kawashi.banco.compartilhado;

/**
 * Regras de documentos brasileiros (CNPJ e CPF). Esta e a regra de negocio mais basica da API:
 * o banco so garante o formato (14 e 11 digitos), quem confere os digitos verificadores e a API.
 */
public final class Documentos {

    private static final int[] PESOS_CNPJ_1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] PESOS_CNPJ_2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private Documentos() {
    }

    /** Remove tudo que nao e digito ("12.345.678/0001-90" vira "12345678000190"). Nulo vira nulo. */
    public static String somenteDigitos(String valor) {
        return valor == null ? null : valor.replaceAll("\\D", "");
    }

    public static boolean cnpjValido(String valor) {
        String d = somenteDigitos(valor);
        if (d == null || d.length() != 14 || todosIguais(d)) {
            return false;
        }
        return digito(d, PESOS_CNPJ_1) == d.charAt(12) - '0'
                && digito(d, PESOS_CNPJ_2) == d.charAt(13) - '0';
    }

    public static boolean cpfValido(String valor) {
        String d = somenteDigitos(valor);
        if (d == null || d.length() != 11 || todosIguais(d)) {
            return false;
        }
        return digito(d, pesosCpf(10)) == d.charAt(9) - '0'
                && digito(d, pesosCpf(11)) == d.charAt(10) - '0';
    }

    /** Os 8 primeiros digitos do CNPJ identificam a empresa; filiais compartilham a raiz da matriz. */
    public static String raizCnpj(String cnpj) {
        return somenteDigitos(cnpj).substring(0, 8);
    }

    /** Digitos 9 a 12 do CNPJ: "0001" e a matriz, "0002" em diante sao filiais. */
    public static String ordemCnpj(String cnpj) {
        return somenteDigitos(cnpj).substring(8, 12);
    }

    private static int[] pesosCpf(int primeiroPeso) {
        int[] pesos = new int[primeiroPeso - 1];
        for (int i = 0; i < pesos.length; i++) {
            pesos[i] = primeiroPeso - i;
        }
        return pesos;
    }

    private static int digito(String digitos, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) {
            soma += (digitos.charAt(i) - '0') * pesos[i];
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private static boolean todosIguais(String d) {
        return d.chars().allMatch(c -> c == d.charAt(0));
    }
}
