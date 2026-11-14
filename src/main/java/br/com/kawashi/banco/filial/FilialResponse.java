package br.com.kawashi.banco.filial;

import java.time.LocalDateTime;

public record FilialResponse(
        Long id,
        Long empresaId,
        String cnpj,
        String nome,
        boolean ativa,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm) {

    public static FilialResponse de(Filial filial) {
        return new FilialResponse(
                filial.getId(),
                filial.getEmpresaId(),
                filial.getCnpj(),
                filial.getNome(),
                filial.isAtiva(),
                filial.getCriadoEm(),
                filial.getAtualizadoEm());
    }
}
