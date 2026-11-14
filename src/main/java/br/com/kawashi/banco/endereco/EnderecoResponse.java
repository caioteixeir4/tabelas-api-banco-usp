package br.com.kawashi.banco.endereco;

import java.time.LocalDateTime;

/** Exatamente um entre filialId e pessoaId vem preenchido: e o dono do endereco. */
public record EnderecoResponse(
        Long id,
        Long filialId,
        Long pessoaId,
        String logradouro,
        String numero,
        String complemento,
        String bairro,
        String cidade,
        String uf,
        String cep,
        boolean principal,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm) {

    public static EnderecoResponse de(Endereco endereco) {
        return new EnderecoResponse(
                endereco.getId(),
                endereco.getFilialId(),
                endereco.getPessoaId(),
                endereco.getLogradouro(),
                endereco.getNumero(),
                endereco.getComplemento(),
                endereco.getBairro(),
                endereco.getCidade(),
                endereco.getUf(),
                endereco.getCep(),
                endereco.isPrincipal(),
                endereco.getCriadoEm(),
                endereco.getAtualizadoEm());
    }
}
