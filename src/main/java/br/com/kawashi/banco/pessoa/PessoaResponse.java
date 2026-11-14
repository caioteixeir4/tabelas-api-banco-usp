package br.com.kawashi.banco.pessoa;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PessoaResponse(
        Long id,
        Long empresaId,
        String tipo,
        String natureza,
        String documento,
        String nome,
        BigDecimal participacao,
        String cargo,
        boolean ativa,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm) {

    public static PessoaResponse de(Pessoa pessoa) {
        return new PessoaResponse(
                pessoa.getId(),
                pessoa.getEmpresaId(),
                pessoa.getTipo().name(),
                pessoa.getNatureza(),
                pessoa.getDocumento(),
                pessoa.getNome(),
                pessoa.getParticipacao(),
                pessoa.getCargo(),
                pessoa.isAtiva(),
                pessoa.getCriadoEm(),
                pessoa.getAtualizadoEm());
    }
}
