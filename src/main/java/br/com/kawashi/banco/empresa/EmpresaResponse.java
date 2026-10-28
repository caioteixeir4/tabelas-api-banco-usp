package br.com.kawashi.banco.empresa;

import java.time.LocalDateTime;

public record EmpresaResponse(
        Long id,
        Long clienteId,
        String cnpj,
        String razaoSocial,
        String nomeFantasia,
        boolean ativa,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm) {

    public static EmpresaResponse de(Empresa empresa) {
        return new EmpresaResponse(
                empresa.getId(),
                empresa.getCliente().getId(),
                empresa.getCnpj(),
                empresa.getRazaoSocial(),
                empresa.getNomeFantasia(),
                empresa.isAtiva(),
                empresa.getCriadoEm(),
                empresa.getAtualizadoEm());
    }
}
