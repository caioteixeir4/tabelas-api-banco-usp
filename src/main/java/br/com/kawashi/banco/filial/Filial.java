package br.com.kawashi.banco.filial;

import org.hibernate.annotations.DynamicUpdate;

import br.com.kawashi.banco.compartilhado.AuditoriaBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Filial de uma empresa. A empresa pai e guardada so pela chave (EMPRESA_ID), sem associacao JPA:
 * assim a resposta nunca depende de carga preguicosa fora da transacao (o open-in-view esta
 * desligado). A chave estrangeira continua existindo no banco.
 */
// @DynamicUpdate: ver o motivo em Empresa (um PUT nao desfaz um "inativar" concorrente).
@Entity
@DynamicUpdate
@Table(name = "FILIAL")
public class Filial extends AuditoriaBase {

    @Column(name = "EMPRESA_ID", nullable = false, updatable = false)
    private Long empresaId;

    /** Somente digitos. Imutavel depois de criada. */
    @Column(name = "CNPJ", nullable = false, length = 14, updatable = false)
    private String cnpj;

    @Column(name = "NOME", nullable = false, length = 150)
    private String nome;

    @Column(name = "ATIVA", nullable = false)
    private boolean ativa = true;

    protected Filial() {
        // exigido pelo JPA
    }

    public Filial(Long empresaId, String cnpj, String nome) {
        this.empresaId = empresaId;
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

    public Long getEmpresaId() {
        return empresaId;
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
