package br.com.kawashi.banco.endereco;

import java.time.LocalDateTime;

public record EnderecoResponse(
        Long id,
        Long clienteId,
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
                // getId() do proxy preguicoso do cliente nao o carrega (a sessao ja fechou aqui).
                endereco.getCliente().getId(),
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
