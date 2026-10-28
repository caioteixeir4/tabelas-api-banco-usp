package br.com.kawashi.banco.contato;

import java.time.LocalDateTime;

public record ContatoResponse(
        Long id,
        Long clienteId,
        String nome,
        String email,
        String telefone,
        boolean principal,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm) {

    /** O servico sempre carrega o cliente antes do contato, entao o id vem sem ida extra ao banco. */
    public static ContatoResponse de(Contato contato) {
        return new ContatoResponse(
                contato.getId(),
                contato.getCliente().getId(),
                contato.getNome(),
                contato.getEmail(),
                contato.getTelefone(),
                contato.isPrincipal(),
                contato.getCriadoEm(),
                contato.getAtualizadoEm());
    }
}
