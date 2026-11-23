package br.com.kawashi.banco.autorizacao;

/**
 * Os codigos de permissao (tabela PERMISSAO, criada pelo repositorio de regras em
 * 14_autorizacao.sql). Permissao nova e uma linha nova la e uma constante nova aqui.
 */
public final class Permissoes {

    public static final String EMPRESA_LER = "EMPRESA_LER";
    public static final String EMPRESA_ALTERAR = "EMPRESA_ALTERAR";
    public static final String FILIAL_LER = "FILIAL_LER";
    public static final String FILIAL_GERENCIAR = "FILIAL_GERENCIAR";
    public static final String PESSOA_LER = "PESSOA_LER";
    public static final String PESSOA_GERENCIAR = "PESSOA_GERENCIAR";
    public static final String DADO_PESSOAL_REVELAR = "DADO_PESSOAL_REVELAR";
    /** Exclusiva do ADMINISTRADOR. */
    public static final String USUARIO_GERENCIAR = "USUARIO_GERENCIAR";
    /** Exclusiva do ADMINISTRADOR: dar cargo e editar perfis. */
    public static final String PERFIL_GERENCIAR = "PERFIL_GERENCIAR";

    private Permissoes() {
    }
}
