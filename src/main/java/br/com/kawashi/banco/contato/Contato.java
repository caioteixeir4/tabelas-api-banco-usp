package br.com.kawashi.banco.contato;

import br.com.kawashi.banco.auxiliar.Dono;
import br.com.kawashi.banco.compartilhado.AuditoriaBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Um meio de contato (e-mail ou telefone) de uma filial ou de uma pessoa. Tabela auxiliar: o dono e
 * guardado em FILIAL_ID ou PESSOA_ID (exatamente uma preenchida) e nunca muda.
 */
@Entity
@Table(name = "CONTATO")
public class Contato extends AuditoriaBase {

    @Column(name = "FILIAL_ID", updatable = false)
    private Long filialId;

    @Column(name = "PESSOA_ID", updatable = false)
    private Long pessoaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "TIPO", nullable = false, length = 20, updatable = false)
    private TipoDeContato tipo;

    /** E-mail em minuscula, ou telefone so com digitos (DDD + numero). */
    @Column(name = "VALOR", nullable = false, length = 150)
    private String valor;

    @Column(name = "PRINCIPAL", nullable = false)
    private boolean principal;

    protected Contato() {
        // exigido pelo JPA
    }

    public Contato(Dono dono, TipoDeContato tipo, String valor, boolean principal) {
        this.filialId = dono.filialId();
        this.pessoaId = dono.pessoaId();
        this.tipo = tipo;
        this.valor = valor;
        this.principal = principal;
    }

    public void atualizar(String valor, boolean principal) {
        this.valor = valor;
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

    public TipoDeContato getTipo() {
        return tipo;
    }

    public String getValor() {
        return valor;
    }

    public boolean isPrincipal() {
        return principal;
    }
}
