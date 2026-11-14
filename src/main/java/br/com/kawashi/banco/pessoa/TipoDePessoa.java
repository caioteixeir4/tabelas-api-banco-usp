package br.com.kawashi.banco.pessoa;

/**
 * O papel de uma pessoa em relacao a empresa. E aqui que fica a separacao que antes era uma tabela
 * para cada papel: um tipo novo e um valor novo nesta lista (e no CHECK da coluna TIPO).
 */
public enum TipoDePessoa {
    CLIENTE, COLABORADOR, SOCIO, FORNECEDOR
}
