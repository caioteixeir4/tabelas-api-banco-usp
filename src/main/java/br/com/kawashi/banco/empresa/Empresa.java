package br.com.kawashi.banco.empresa;

import org.hibernate.annotations.DynamicUpdate;

import br.com.kawashi.banco.compartilhado.AuditoriaBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Uma empresa (sempre a MATRIZ, CNPJ com ordem 0001). Filiais e pessoas penduram nela.
 *
 * <p>{@code @DynamicUpdate}: o UPDATE grava so as colunas que mudaram. Sem isso, um PUT (que muda a
 * razao social) desfazia um "inativar" concorrente, porque regravava tambem a coluna ATIVA com o
 * valor lido antes.
 */
@Entity
@DynamicUpdate
@Table(name = "EMPRESA")
public class Empresa extends AuditoriaBase {

    /** Somente digitos. Imutavel depois de criada. */
    @Column(name = "CNPJ", nullable = false, length = 14, updatable = false)
    private String cnpj;

    @Column(name = "RAZAO_SOCIAL", nullable = false, length = 200)
    private String razaoSocial;

    @Column(name = "NOME_FANTASIA", length = 200)
    private String nomeFantasia;

    @Column(name = "ATIVA", nullable = false)
    private boolean ativa = true;

    protected Empresa() {
        // exigido pelo JPA
    }

    public Empresa(String cnpj, String razaoSocial, String nomeFantasia) {
        this.cnpj = cnpj;
        this.razaoSocial = razaoSocial;
        this.nomeFantasia = nomeFantasia;
    }

    public void atualizar(String razaoSocial, String nomeFantasia) {
        this.razaoSocial = razaoSocial;
        this.nomeFantasia = nomeFantasia;
    }

    public void inativar() {
        this.ativa = false;
    }

    public void reativar() {
        this.ativa = true;
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

    public boolean isAtiva() {
        return ativa;
    }
}
