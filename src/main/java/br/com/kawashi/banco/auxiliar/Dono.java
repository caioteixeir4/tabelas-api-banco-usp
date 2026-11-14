package br.com.kawashi.banco.auxiliar;

import org.springframework.data.jpa.domain.Specification;

/**
 * O dono de um contato ou de um endereco: uma filial ou uma pessoa, nunca os dois. No banco sao
 * duas colunas (FILIAL_ID e PESSOA_ID) com um CHECK exigindo exatamente uma preenchida; aqui, um
 * valor so, para as regras nao precisarem ser escritas duas vezes.
 */
public record Dono(Tipo tipo, Long id) {

    public enum Tipo {
        FILIAL, PESSOA
    }

    public static Dono filial(Long id) {
        return new Dono(Tipo.FILIAL, id);
    }

    public static Dono pessoa(Long id) {
        return new Dono(Tipo.PESSOA, id);
    }

    /** Reconstroi o dono a partir das duas colunas de uma linha ja gravada. */
    public static Dono de(Long filialId, Long pessoaId) {
        return filialId != null ? filial(filialId) : pessoa(pessoaId);
    }

    public Long filialId() {
        return tipo == Tipo.FILIAL ? id : null;
    }

    public Long pessoaId() {
        return tipo == Tipo.PESSOA ? id : null;
    }

    /** "a filial 12" ou "a pessoa 12", para mensagens. */
    public String descricao() {
        return (tipo == Tipo.FILIAL ? "a filial " : "a pessoa ") + id;
    }

    /** Filtro "as linhas deste dono" para qualquer entidade que tenha filialId e pessoaId. */
    public <T> Specification<T> filtro() {
        String atributo = tipo == Tipo.FILIAL ? "filialId" : "pessoaId";
        return (raiz, consulta, cb) -> cb.equal(raiz.get(atributo), id);
    }
}
