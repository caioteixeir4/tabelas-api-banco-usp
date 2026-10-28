package br.com.kawashi.banco.cliente;

import br.com.kawashi.banco.compartilhado.AuditoriaBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "CLIENTE")
public class Cliente extends AuditoriaBase {

    /** Somente digitos. Imutavel depois de criado (regra no servico; aqui nem tem setter). */
    @Column(name = "CNPJ", nullable = false, length = 14, updatable = false)
    private String cnpj;

    @Column(name = "RAZAO_SOCIAL", nullable = false, length = 200)
    private String razaoSocial;

    @Column(name = "NOME_FANTASIA", length = 200)
    private String nomeFantasia;

    @Column(name = "ATIVO", nullable = false)
    private boolean ativo = true;

    protected Cliente() {
        // exigido pelo JPA
    }

    public Cliente(String cnpj, String razaoSocial, String nomeFantasia) {
        this.cnpj = cnpj;
        this.razaoSocial = razaoSocial;
        this.nomeFantasia = nomeFantasia;
    }

    public void atualizar(String razaoSocial, String nomeFantasia) {
        this.razaoSocial = razaoSocial;
        this.nomeFantasia = nomeFantasia;
    }

    public void inativar() {
        this.ativo = false;
    }

    public void reativar() {
        this.ativo = true;
    }

    public String getCnpj() {
        return cnpj;
    }

    public String getRazaoSocial() {
        return razaoSocial;
    }

    public String getNomeFantasia() {
        return nomeFantasia;
    }

    public boolean isAtivo() {
        return ativo;
    }
}
