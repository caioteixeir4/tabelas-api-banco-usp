package br.com.kawashi.banco.cliente;

import java.time.LocalDateTime;

public record ClienteResponse(
        Long id,
        String cnpj,
        String razaoSocial,
        String nomeFantasia,
        boolean ativo,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm) {

    public static ClienteResponse de(Cliente cliente) {
        return new ClienteResponse(
                cliente.getId(),
                cliente.getCnpj(),
                cliente.getRazaoSocial(),
                cliente.getNomeFantasia(),
                cliente.isAtivo(),
                cliente.getCriadoEm(),
                cliente.getAtualizadoEm());
    }
}
