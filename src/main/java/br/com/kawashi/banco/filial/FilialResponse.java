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
                // getId() de uma empresa carregada de forma preguicosa nao inicializa o proxy, entao
                // funciona mesmo depois que a transacao do servico terminou (open-in-view esta desligado).
                filial.getEmpresa().getId(),
                filial.getCnpj(),
                filial.getNome(),
                filial.isAtiva(),
                filial.getCriadoEm(),
                filial.getAtualizadoEm());
    }
}
