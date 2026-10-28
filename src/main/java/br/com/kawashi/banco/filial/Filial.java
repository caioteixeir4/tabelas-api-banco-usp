package br.com.kawashi.banco.filial;

import br.com.kawashi.banco.compartilhado.AuditoriaBase;
import br.com.kawashi.banco.empresa.Empresa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Uma filial de uma empresa. O CNPJ tem a mesma raiz (8 primeiros digitos) da empresa e uma ordem
 * de 0002 em diante (0001 e a matriz). Empresa e CNPJ nao mudam depois de criada a filial.
 */
@Entity
@Table(name = "FILIAL")
public class Filial extends AuditoriaBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "EMPRESA_ID", nullable = false, updatable = false)
    private Empresa empresa;

    /** Somente digitos. Imutavel depois de criada (regra no servico; aqui nem tem setter). */
    @Column(name = "CNPJ", nullable = false, length = 14, updatable = false)
    private String cnpj;

    @Column(name = "NOME", nullable = false, length = 150)
    private String nome;

    @Column(name = "ATIVA", nullable = false)
    private boolean ativa = true;

    protected Filial() {
        // exigido pelo JPA
    }

    public Filial(Empresa empresa, String cnpj, String nome) {
        this.empresa = empresa;
        this.cnpj = cnpj;
        this.nome = nome;
    }

    public void atualizar(String nome) {
        this.nome = nome;
    }

    public void inativar() {
        this.ativa = false;
    }

    public void reativar() {
        this.ativa = true;
    }

    public Empresa getEmpresa() {
        return empresa;
    }

    public String getCnpj() {
        return cnpj;
    }

    public String getNome() {
        return nome;
    }

    public boolean isAtiva() {
        return ativa;
    }
}
