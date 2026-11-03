package br.com.kawashi.banco.contato;

import java.util.Locale;

import br.com.kawashi.banco.compartilhado.Textos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corpo de criacao e de atualizacao. O telefone aceita pontuacao ("(11) 98765-4321") e e guardado so
 * com digitos; o email e guardado em minuscula. "principal" nulo vale false. A exigencia de pelo
 * menos um meio de contato (email ou telefone) e regra do servico, nao deste formato.
 */
public record ContatoRequest(
        @NotBlank(message = "O nome é obrigatório")
        @Size(max = 150, message = "O nome aceita no máximo 150 caracteres")
        String nome,

        @Email(message = "O e-mail é inválido")
        @Size(max = 150, message = "O e-mail aceita no máximo 150 caracteres")
        String email,

        @Size(max = 30, message = "O telefone aceita no máximo 30 caracteres")
        @Telefone
        String telefone,

        Boolean principal) {

    public ContatoRequest {
        // O email e guardado em minuscula. Normalizar ANTES da validacao faz o @Size conferir o texto
        // final: em alguns alfabetos a minuscula tem mais caracteres que a maiuscula (o "İ" turco vira
        // dois), e conferir o tamanho antes deixava passar um email que depois estourava a coluna.
        nome = Textos.aparar(nome);
        telefone = Textos.aparar(telefone);
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }
    }
}
