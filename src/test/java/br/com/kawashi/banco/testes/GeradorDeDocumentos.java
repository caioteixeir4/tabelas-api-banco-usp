package br.com.kawashi.banco.testes;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Gera CNPJ e CPF VALIDOS e unicos para os testes (nunca documentos reais). Reimplementa o calculo
 * dos digitos verificadores de proposito, sem reaproveitar o codigo de producao: assim o teste do
 * validador nao concorda consigo mesmo por construcao.
 */
public final class GeradorDeDocumentos {

    private GeradorDeDocumentos() {
    }

    /** CNPJ de matriz (ordem 0001) com raiz aleatoria, so digitos. */
    public static String cnpj() {
        return cnpj(raizDeCnpj(), "0001");
    }

    /** CNPJ com a raiz (8 digitos) e a ordem (4 digitos) informadas, so digitos. */
    public static String cnpj(String raiz, String ordem) {
        String base = raiz + ordem;
        int d1 = digito(base, new int[] {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = digito(base + d1, new int[] {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return base + d1 + d2;
    }

    /** Oito digitos aleatorios, garantidamente nao todos iguais. */
    public static String raizDeCnpj() {
        String raiz;
        do {
            raiz = digitosAleatorios(8);
        } while (todosIguais(raiz));
        return raiz;
    }

    public static String cpf() {
        String base;
        do {
            base = digitosAleatorios(9);
        } while (todosIguais(base));
        int d1 = digito(base, new int[] {10, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = digito(base + d1, new int[] {11, 10, 9, 8, 7, 6, 5, 4, 3, 2});
        return base + d1 + d2;
    }

    /** "12345678000190" vira "12.345.678/0001-90". */
    public static String formatarCnpj(String cnpj) {
        return cnpj.substring(0, 2) + "." + cnpj.substring(2, 5) + "." + cnpj.substring(5, 8) + "/"
                + cnpj.substring(8, 12) + "-" + cnpj.substring(12);
    }

    /** "12345678909" vira "123.456.789-09". */
    public static String formatarCpf(String cpf) {
        return cpf.substring(0, 3) + "." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-" + cpf.substring(9);
    }

    /** Troca o ultimo digito por outro, tornando o documento invalido. */
    public static String comDigitoVerificadorErrado(String documento) {
        char ultimo = documento.charAt(documento.length() - 1);
        char trocado = ultimo == '0' ? '1' : '0';
        return documento.substring(0, documento.length() - 1) + trocado;
    }

    private static int digito(String digitos, int[] pesos) {
        int soma = 0;
        for (int i = 0; i < pesos.length; i++) {
            soma += (digitos.charAt(i) - '0') * pesos[i];
        }
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }

    private static String digitosAleatorios(int quantidade) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < quantidade; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.toString();
    }

    private static boolean todosIguais(String digitos) {
        return digitos.chars().allMatch(c -> c == digitos.charAt(0));
    }
}
