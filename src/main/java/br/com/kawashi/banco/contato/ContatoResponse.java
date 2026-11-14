package br.com.kawashi.banco.contato;

import java.time.LocalDateTime;

/** Exatamente um entre filialId e pessoaId vem preenchido: e o dono do contato. */
public record ContatoResponse(
        Long id,
        Long filialId,
        Long pessoaId,
        String tipo,
        String valor,
        boolean principal,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm) {

    public static ContatoResponse de(Contato contato) {
        return new ContatoResponse(
                contato.getId(),
                contato.getFilialId(),
                contato.getPessoaId(),
                contato.getTipo().name(),
                contato.getValor(),
                contato.isPrincipal(),
                contato.getCriadoEm(),
                contato.getAtualizadoEm());
    }
}
