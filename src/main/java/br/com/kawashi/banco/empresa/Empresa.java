package br.com.kawashi.banco.empresa;

import br.com.kawashi.banco.cliente.Cliente;
import br.com.kawashi.banco.compartilhado.AuditoriaBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Uma empresa (sempre a MATRIZ, CNPJ com ordem 0001) de um cliente. As filiais e os socios
 * penduram nela. Entidade e repositorio ficam na fundacao porque Filial e Socio dependem deles; o
 * servico, o controlador e os DTOs sao da fatia de Empresa.
 */
@Entity
@Table(name = "EMPRESA")
public class Empresa extends AuditoriaBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CLIENTE_ID", nullable = false, updatable = false)
    private Cliente cliente;

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

    public Empresa(Cliente cliente, String cnpj, String razaoSocial, String nomeFantasia) {
        this.cliente = cliente;
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

    public Cliente getCliente() {
        return cliente;
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
