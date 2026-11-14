package br.com.kawashi.banco.endereco;

import br.com.kawashi.banco.auxiliar.Dono;
import br.com.kawashi.banco.compartilhado.AuditoriaBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Um endereco de uma filial ou de uma pessoa. Tabela auxiliar: o dono e guardado em FILIAL_ID ou
 * PESSOA_ID (exatamente uma preenchida) e nunca muda. UF e CEP ja chegam normalizados do servico
 * (UF em maiuscula, CEP com 8 digitos); o banco so confere o formato.
 */
@Entity
@Table(name = "ENDERECO")
public class Endereco extends AuditoriaBase {

    @Column(name = "FILIAL_ID", updatable = false)
    private Long filialId;

    @Column(name = "PESSOA_ID", updatable = false)
    private Long pessoaId;

    @Column(name = "LOGRADOURO", nullable = false, length = 200)
    private String logradouro;

    @Column(name = "NUMERO", length = 20)
    private String numero;

    @Column(name = "COMPLEMENTO", length = 100)
    private String complemento;

    @Column(name = "BAIRRO", length = 100)
    private String bairro;

    @Column(name = "CIDADE", nullable = false, length = 100)
    private String cidade;

    @Column(name = "UF", nullable = false, length = 2)
    private String uf;

    /** Somente digitos (8). */
    @Column(name = "CEP", nullable = false, length = 8)
    private String cep;

    @Column(name = "PRINCIPAL", nullable = false)
    private boolean principal;

    protected Endereco() {
        // exigido pelo JPA
    }

    public Endereco(Dono dono, String logradouro, String numero, String complemento, String bairro,
            String cidade, String uf, String cep, boolean principal) {
        this.filialId = dono.filialId();
        this.pessoaId = dono.pessoaId();
        definir(logradouro, numero, complemento, bairro, cidade, uf, cep, principal);
    }

    public void atualizar(String logradouro, String numero, String complemento, String bairro, String cidade,
            String uf, String cep, boolean principal) {
        definir(logradouro, numero, complemento, bairro, cidade, uf, cep, principal);
    }

    private void definir(String logradouro, String numero, String complemento, String bairro, String cidade,
            String uf, String cep, boolean principal) {
        this.logradouro = logradouro;
        this.numero = numero;
        this.complemento = complemento;
        this.bairro = bairro;
        this.cidade = cidade;
        this.uf = uf;
        this.cep = cep;
        this.principal = principal;
    }

    public void rebaixar() {
        this.principal = false;
    }

    public Dono dono() {
        return Dono.de(filialId, pessoaId);
    }

    public Long getFilialId() {
        return filialId;
    }

    public Long getPessoaId() {
        return pessoaId;
    }

    public String getLogradouro() {
        return logradouro;
    }

    public String getNumero() {
        return numero;
    }

    public String getComplemento() {
        return complemento;
    }

    public String getBairro() {
        return bairro;
    }

    public String getCidade() {
        return cidade;
    }

    public String getUf() {
        return uf;
    }

    public String getCep() {
        return cep;
    }

    public boolean isPrincipal() {
        return principal;
    }
}
