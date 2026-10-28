package br.com.kawashi.banco.socio;

import java.math.BigDecimal;

import br.com.kawashi.banco.compartilhado.AuditoriaBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Socio de uma empresa. A empresa pai e guardada so pela chave (EMPRESA_ID), sem associacao JPA:
 * assim a resposta nunca depende de carga preguicosa fora da transacao (o open-in-view esta
 * desligado). A chave estrangeira continua existindo no banco.
 */
@Entity
@Table(name = "SOCIO")
public class Socio extends AuditoriaBase {

    /** Chave do pai. Imutavel depois de criado. */
    @Column(name = "EMPRESA_ID", nullable = false, updatable = false)
    private Long empresaId;

    @Column(name = "NOME", nullable = false, length = 150)
    private String nome;

    /** Somente digitos. Imutavel depois de criado (regra no servico; aqui nem tem setter). */
    @Column(name = "CPF", nullable = false, length = 11, updatable = false)
    private String cpf;

    /** Percentual de 0,01 a 100,00, sempre com 2 casas decimais. */
    @Column(name = "PARTICIPACAO", nullable = false, precision = 5, scale = 2)
    private BigDecimal participacao;

    protected Socio() {
        // exigido pelo JPA
    }

    public Socio(Long empresaId, String nome, String cpf, BigDecimal participacao) {
        this.empresaId = empresaId;
        this.nome = nome;
        this.cpf = cpf;
        this.participacao = participacao;
    }

    public void atualizar(String nome, BigDecimal participacao) {
        this.nome = nome;
        this.participacao = participacao;
    }

    public Long getEmpresaId() {
        return empresaId;
    }

    public String getNome() {
        return nome;
    }

    public String getCpf() {
        return cpf;
    }

    public BigDecimal getParticipacao() {
        return participacao;
    }
}
