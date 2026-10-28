package br.com.kawashi.banco.contato;

import br.com.kawashi.banco.cliente.Cliente;
import br.com.kawashi.banco.compartilhado.AuditoriaBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Uma pessoa de contato de um cliente. Email e telefone sao opcionais individualmente, mas o servico
 * exige pelo menos um dos dois; o telefone e guardado so com digitos (10 ou 11).
 */
@Entity
@Table(name = "CONTATO")
public class Contato extends AuditoriaBase {

    /** O cliente dono do contato nunca muda: o contato nao "troca de cliente". */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CLIENTE_ID", nullable = false, updatable = false)
    private Cliente cliente;

    @Column(name = "NOME", nullable = false, length = 150)
    private String nome;

    /** Sempre em minuscula. */
    @Column(name = "EMAIL", length = 150)
    private String email;

    /** Somente digitos (DDD + numero). */
    @Column(name = "TELEFONE", length = 11)
    private String telefone;

    @Column(name = "PRINCIPAL", nullable = false)
    private boolean principal;

    protected Contato() {
        // exigido pelo JPA
    }

    public Contato(Cliente cliente, String nome, String email, String telefone, boolean principal) {
        this.cliente = cliente;
        this.nome = nome;
        this.email = email;
        this.telefone = telefone;
        this.principal = principal;
    }

    public void atualizar(String nome, String email, String telefone, boolean principal) {
        this.nome = nome;
        this.email = email;
        this.telefone = telefone;
        this.principal = principal;
    }

    public void rebaixar() {
        this.principal = false;
    }

    /**
     * Nao existe um "getClienteId()" de proposito: o Spring Data trata qualquer getter como
     * propriedade consultavel e "findByClienteId" passaria a procurar um atributo "clienteId" que o
     * Hibernate nao conhece (o caminho certo e cliente.id).
     */
    public Cliente getCliente() {
        return cliente;
    }

    public String getNome() {
        return nome;
    }

    public String getEmail() {
        return email;
    }

    public String getTelefone() {
        return telefone;
    }

    public boolean isPrincipal() {
        return principal;
    }
}
