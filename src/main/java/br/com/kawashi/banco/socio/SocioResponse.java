package br.com.kawashi.banco.socio;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SocioResponse(
        Long id,
        Long empresaId,
        String nome,
        String cpf,
        BigDecimal participacao,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm) {

    public static SocioResponse de(Socio socio) {
        return new SocioResponse(
                socio.getId(),
                socio.getEmpresaId(),
                socio.getNome(),
                socio.getCpf(),
                socio.getParticipacao(),
                socio.getCriadoEm(),
                socio.getAtualizadoEm());
    }
}
