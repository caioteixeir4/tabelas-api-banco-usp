package br.com.kawashi.banco.contato;

import org.hibernate.annotations.DynamicUpdate;

import br.com.kawashi.banco.auxiliar.Dono;
import br.com.kawashi.banco.compartilhado.AuditoriaBase;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Um meio de contato (e-mail ou telefone) de uma filial ou de uma pessoa. Tabela auxiliar: o dono e
 * guardado em FILIAL_ID ou PESSOA_ID (exatamente uma preenchida) e nunca muda.
 */
// @DynamicUpdate: o UPDATE leva so as colunas que mudaram. Sem ele, mudar apenas o "principal"
// regravaria VALOR_CIFRADO, e como a cifra sorteia um numero novo a cada gravacao a coluna mudaria
// sem o dado ter mudado (ruido para quem audita a tabela).
@Entity
@DynamicUpdate
@Table(name = "CONTATO")
public class Contato extends AuditoriaBase {

    @Column(name = "FILIAL_ID", updatable = false)
    private Long filialId;

    @Column(name = "PESSOA_ID", updatable = false)
    private Long pessoaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "TIPO", nullable = false, length = 20, updatable = false)
    private TipoDeContato tipo;

    /**
     * E-mail em minuscula, ou telefone so com digitos (DDD + numero). Aqui, em memoria, esta em
     * claro; no banco vai cifrado (ver {@link ValorCifrado}).
     */
    @Convert(converter = ValorCifrado.class)
    @Column(name = "VALOR_CIFRADO", nullable = false, length = 1000)
    private String valor;

    /** Impressao digital do valor: e por ela que se sabe se o contato ja existe no mesmo dono. */
    @Column(name = "VALOR_INDICE", nullable = false, length = 64)
    private String valorIndice;

    @Column(name = "PRINCIPAL", nullable = false)
    private boolean principal;

    protected Contato() {
        // exigido pelo JPA
    }

    public Contato(Dono dono, TipoDeContato tipo, String valor, String valorIndice, boolean principal) {
        this.filialId = dono.filialId();
        this.pessoaId = dono.pessoaId();
        this.tipo = tipo;
        this.valor = valor;
        this.valorIndice = valorIndice;
        this.principal = principal;
    }

    public void atualizar(String valor, String valorIndice, boolean principal) {
        // So troca o valor quando ele muda de verdade: a cifra sorteia um numero novo a cada
        // gravacao, e regravar o mesmo valor mudaria a coluna sem mudar o dado.
        if (!valor.equals(this.valor)) {
            this.valor = valor;
            this.valorIndice = valorIndice;
        }
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
