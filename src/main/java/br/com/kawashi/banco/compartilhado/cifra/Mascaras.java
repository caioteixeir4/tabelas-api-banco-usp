package br.com.kawashi.banco.compartilhado.cifra;

/**
 * Como os dados pessoais aparecem nas respostas da API: mascarados. O valor inteiro so sai pelos
 * caminhos de "revelar" ({@code /api/pessoas/{id}/documento}, {@code /api/contatos/{id}/valor}), que
 * ficam no log de acesso. Assim uma listagem, um print de tela ou um log de quem consome a API nao
 * carrega CPF, e-mail nem telefone completos.
 *
 * <p>A mascara nao e seguranca do dado guardado (isso e a cifra): e reducao de exposicao no uso do
 * dia a dia. O CPF segue o formato usado em publicacoes oficiais (tres primeiros e dois ultimos
 * digitos escondidos).
 */
public final class Mascaras {

    private Mascaras() {
    }

    /** CPF (11 digitos) vira {@code ***.456.789-**}; CNPJ (14) vira {@code **.345.678/0001-**}. */
    public static String documento(String digitos) {
        if (digitos == null) {
            return null;
        }
        if (digitos.length() == 11) {
            return "***." + digitos.substring(3, 6) + "." + digitos.substring(6, 9) + "-**";
        }
        if (digitos.length() == 14) {
            return "**." + digitos.substring(2, 5) + "." + digitos.substring(5, 8) + "/" + digitos.substring(8, 12) + "-**";
        }
        return "*".repeat(digitos.length());
    }

    /** {@code fulano@exemplo.com.br} vira {@code f***@exemplo.com.br}; nome de 1 ou 2 letras some inteiro. */
    public static String email(String email) {
        if (email == null) {
            return null;
        }
        int arroba = email.indexOf('@');
        if (arroba < 0) {
            return "***";
        }
        String inicio = arroba > 2 ? email.substring(0, 1) : "";
        return inicio + "***" + email.substring(arroba);
    }

    /** Telefone so com digitos (DDD + numero): {@code 11987654321} vira {@code (11) *****-4321}. */
    public static String telefone(String digitos) {
        if (digitos == null) {
            return null;
        }
        if (digitos.length() < 10) {
            return "*".repeat(digitos.length());
        }
        int escondidos = digitos.length() - 6;
        return "(" + digitos.substring(0, 2) + ") " + "*".repeat(escondidos) + "-" + digitos.substring(digitos.length() - 4);
    }

    /**
     * Diz se um texto recebido num pedido e uma mascara devolvida pela API (tem dois asteriscos
     * seguidos), e nao um valor de verdade. Nenhum documento ou telefone valido tem asterisco.
     */
    public static boolean eMascara(String texto) {
        return texto != null && texto.contains("**");
    }
}
