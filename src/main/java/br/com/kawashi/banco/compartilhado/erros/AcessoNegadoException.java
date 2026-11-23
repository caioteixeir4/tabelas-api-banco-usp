package br.com.kawashi.banco.compartilhado.erros;

/** O usuario esta logado, mas o perfil dele nesta empresa nao permite a acao (403). */
public class AcessoNegadoException extends RuntimeException {

    private final String titulo;

    public AcessoNegadoException(String titulo, String mensagem) {
        super(mensagem);
        this.titulo = titulo;
    }

    public String getTitulo() {
        return titulo;
    }
}
