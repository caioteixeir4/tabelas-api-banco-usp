package br.com.kawashi.banco.compartilhado;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * Base de todas as entidades: chave gerada pelo banco (identity do Oracle) e carimbos de criacao e
 * atualizacao. Quem preenche os carimbos e a API (UTC), nao um trigger do banco: o banco guarda,
 * a regra mora aqui.
 */
@MappedSuperclass
public abstract class AuditoriaBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ID")
    private Long id;

    @Column(name = "CRIADO_EM", nullable = false, updatable = false)
    private LocalDateTime criadoEm;

    @Column(name = "ATUALIZADO_EM", nullable = false)
    private LocalDateTime atualizadoEm;

    @PrePersist
    void aoCriar() {
        LocalDateTime agora = agora();
        this.criadoEm = agora;
        this.atualizadoEm = agora;
    }

    @PreUpdate
    void aoAtualizar() {
        this.atualizadoEm = agora();
    }

    /**
     * Na precisao da coluna (TIMESTAMP(6), microssegundos). Sem truncar, a resposta do POST
     * devolvia o valor em memoria (ate 9 casas) e o GET seguinte, o valor arredondado pelo Oracle:
     * a mesma data aparecia com dois textos diferentes.
     */
    private static LocalDateTime agora() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getCriadoEm() {
        return criadoEm;
    }

    public LocalDateTime getAtualizadoEm() {
        return atualizadoEm;
    }
}
