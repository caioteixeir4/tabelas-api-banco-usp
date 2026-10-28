package br.com.kawashi.banco.endereco;

import br.com.kawashi.banco.cliente.Cliente;
import br.com.kawashi.banco.compartilhado.AuditoriaBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Um endereco de um cliente. O cliente dono nao muda (updatable=false). UF e CEP ja chegam
 * normalizados do servico (UF em maiuscula, CEP com 8 digitos); o banco so confere o formato.
 * Quem garante "no maximo um principal por cliente" e o servico, nao o banco.
 */
@Entity
@Table(name = "ENDERECO")
public class Endereco extends AuditoriaBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CLIENTE_ID", nullable = false, updatable = false)
    private Cliente cliente;

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

    public Endereco(Cliente cliente, String logradouro, String numero, String complemento, String bairro,
            String cidade, String uf, String cep, boolean principal) {
        this.cliente = cliente;
        this.logradouro = logradouro;
        this.numero = numero;
        this.complemento = complemento;
        this.bairro = bairro;
        this.cidade = cidade;
        this.uf = uf;
        this.cep = cep;
        this.principal = principal;
    }

    public void atualizar(String logradouro, String numero, String complemento, String bairro, String cidade,
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

    public Cliente getCliente() {
        return cliente;
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
