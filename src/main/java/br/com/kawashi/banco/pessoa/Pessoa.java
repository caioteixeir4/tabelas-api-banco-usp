package br.com.kawashi.banco.pessoa;

import java.math.BigDecimal;

import org.hibernate.annotations.DynamicUpdate;

import br.com.kawashi.banco.compartilhado.AuditoriaBase;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Uma pessoa (fisica ou juridica) ligada a uma empresa, com um papel: cliente, colaborador, socio
 * ou fornecedor. A mesma pessoa em duas empresas, ou com dois papeis, sao duas linhas.
 */
// @DynamicUpdate: ver o motivo em Empresa (um PUT nao desfaz um "inativar" concorrente).
@Entity
@DynamicUpdate
@Table(name = "PESSOA")
public class Pessoa extends AuditoriaBase {

    @Column(name = "EMPRESA_ID", nullable = false, updatable = false)
    private Long empresaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "TIPO", nullable = false, length = 20, updatable = false)
    private TipoDePessoa tipo;

    /** "F" (fisica, CPF) ou "J" (juridica, CNPJ). */
    @Column(name = "NATUREZA", nullable = false, length = 1, updatable = false)
    private String natureza;

    /**
     * Somente digitos: 11 (CPF) ou 14 (CNPJ). Imutavel depois de criado. Aqui, em memoria, esta em
     * claro; no banco vai cifrado (ver {@link DocumentoCifrado}).
     */
    @Convert(converter = DocumentoCifrado.class)
    @Column(name = "DOCUMENTO_CIFRADO", nullable = false, length = 100, updatable = false)
    private String documento;

    /** Impressao digital do documento: e por ela que o banco garante a unicidade e a API procura. */
    @Column(name = "DOCUMENTO_INDICE", nullable = false, length = 64, updatable = false)
    private String documentoIndice;

    @Column(name = "NOME", nullable = false, length = 200)
    private String nome;

    /** So para SOCIO: percentual de 0,01 a 100,00. */
    @Column(name = "PARTICIPACAO", precision = 5, scale = 2)
    private BigDecimal participacao;

    /** So para COLABORADOR. */
    @Column(name = "CARGO", length = 100)
    private String cargo;

    @Column(name = "ATIVA", nullable = false)
    private boolean ativa = true;

    protected Pessoa() {
        // exigido pelo JPA
    }

    public Pessoa(Long empresaId, TipoDePessoa tipo, String natureza, String documento, String documentoIndice,
            String nome, BigDecimal participacao, String cargo) {
        this.empresaId = empresaId;
        this.tipo = tipo;
        this.natureza = natureza;
        this.documento = documento;
        this.documentoIndice = documentoIndice;
        this.nome = nome;
        this.participacao = participacao;
        this.cargo = cargo;
    }

    public void atualizar(String nome, BigDecimal participacao, String cargo) {
        this.nome = nome;
        this.participacao = participacao;
        this.cargo = cargo;
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

    public TipoDePessoa getTipo() {
        return tipo;
    }

    public String getNatureza() {
        return natureza;
    }

    public String getDocumento() {
        return documento;
    }

    public String getNome() {
        return nome;
    }

    public BigDecimal getParticipacao() {
        return participacao;
    }

    public String getCargo() {
        return cargo;
    }

    public boolean isAtiva() {
        return ativa;
    }
}
