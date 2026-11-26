package br.com.kawashi.banco.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;

/** Sem banco e sem Spring: o hash da senha, as regras da senha e do nome de usuario, e o TOTP. */
class SenhasETotpTest {

    private final Senhas senhas = new Senhas();
    private final Totp totp = new Totp();

    // ---- hash ---------------------------------------------------------------------------------------

    @Test
    void hashEArgon2idComSalAleatorioEConfere() {
        String a = senhas.hash("Cavalo-Bateria-Grampo-42");
        String b = senhas.hash("Cavalo-Bateria-Grampo-42");
        assertThat(a).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").isNotEqualTo(b);
        assertThat(senhas.confere("Cavalo-Bateria-Grampo-42", a)).isTrue();
        assertThat(senhas.confere("cavalo-bateria-grampo-42", a)).isFalse();
        assertThat(senhas.confere("Cavalo-Bateria-Grampo-42", null)).isFalse();
        assertThat(senhas.confere("x", "$2a$10$bcrypt-nao-e-aceito")).isFalse();
    }

    // ---- regras da senha ------------------------------------------------------------------------------

    @Test
    void senhaFracaERecusada() {
        String email = "caio.teixeira@exemplo.com.br";
        String nome = "Caio Teixeira da Silva";
        for (String fraca : new String[] {"curta", "1234567890", "Senha12345", "aaaaaaaaaaaa", "ababababab", "meu-caio.teixeira-1",
                "Teixeira-2026!", "SILVA-forte-99", "Cáio-é-forte-123"}) {
            assertThatThrownBy(() -> senhas.exigirBoa(fraca, 10, email, nome)).as(fraca).isInstanceOf(RegraDeNegocioException.class);
        }
        assertThatThrownBy(() -> senhas.exigirBoa("x".repeat(201) + "y", 10, email, nome)).isInstanceOf(RegraDeNegocioException.class);
        assertThatCode(() -> senhas.exigirBoa("Cavalo-Bateria-Grampo-42", 10, email, nome)).doesNotThrowAnyException();
        // "da" (2 letras) nao conta como parte do nome.
        assertThatCode(() -> senhas.exigirBoa("pao-da-padaria-77", 10, email, nome)).doesNotThrowAnyException();
    }

    @Test
    void nomeDeUsuarioNaoPodeSerONome() {
        String nome = "Caio Teixeira da Silva";
        for (String ruim : new String[] {"caio", "caio_teixeira", "caio.silva", "teixeira-silva", "caioteixeirasilva", "caio.teixeira.da.silva", "caio123",
                "123456789", "ab", "Caio Teixeira", "joão"}) {
            assertThatThrownBy(() -> ServicoDeSenha.validarNomeUsuario(ruim, nome)).as(ruim).isInstanceOf(RegraDeNegocioException.class);
        }
        assertThat(ServicoDeSenha.validarNomeUsuario("  CT.Dev  ", nome)).isEqualTo("ct.dev");
        assertThat(ServicoDeSenha.validarNomeUsuario("kawashi_caio_tx", nome)).isEqualTo("kawashi_caio_tx");
    }

    @Test
    void celularNormalizaComCodigoDoPais() {
        assertThat(Usuarios.normalizarTelefone("(11) 98765-4321")).isEqualTo("5511987654321");
        assertThat(Usuarios.normalizarTelefone("+55 11 98765-4321")).isEqualTo("5511987654321");
        assertThat(Usuarios.normalizarTelefone("1133334444")).isEqualTo("551133334444");
        assertThat(Usuarios.normalizarTelefone("123456789")).isNull();
        assertThat(Usuarios.normalizarTelefone("caio_teixeira")).isNull();
        assertThat(Usuarios.mascararTelefone("5511987654321")).isEqualTo("+55 •••••••4321");
    }

    // ---- TOTP ----------------------------------------------------------------------------------------

    /** RFC 6238, apendice B (SHA-1, segredo "12345678901234567890"): os 6 ultimos digitos de cada vetor. */
    @Test
    void codigoBateComOsVetoresDaRfc6238() {
        String segredo = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        assertThat(segredo).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(totp.codigoDoPasso(segredo, 59 / 30)).isEqualTo("287082");
        assertThat(totp.codigoDoPasso(segredo, 1111111109L / 30)).isEqualTo("081804");
        assertThat(totp.codigoDoPasso(segredo, 1234567890L / 30)).isEqualTo("005924");
        assertThat(totp.codigoDoPasso(segredo, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void aceitaUmPassoDeCadaLadoENaoAceitaPassoJaUsado() {
        String segredo = totp.novoSegredo();
        assertThat(segredo).hasSize(32).matches("[A-Z2-7]+");
        long agora = 1_800_000_000L;
        long passo = agora / 30;
        assertThat(totp.confere(segredo, totp.codigoDoPasso(segredo, passo - 1), null, agora)).hasValue(passo - 1);
        assertThat(totp.confere(segredo, totp.codigoDoPasso(segredo, passo + 1), null, agora)).hasValue(passo + 1);
        assertThat(totp.confere(segredo, totp.codigoDoPasso(segredo, passo + 2), null, agora)).isEmpty();
        assertThat(totp.confere(segredo, totp.codigoDoPasso(segredo, passo), passo, agora)).isEmpty();
        assertThat(totp.confere(segredo, " " + totp.codigoDoPasso(segredo, passo) + " ", passo - 1, agora)).hasValue(passo);
        assertThat(totp.confere(segredo, "12345", null, agora)).isEmpty();
        assertThat(totp.confere(segredo, "abcdef", null, agora)).isEmpty();
    }

    @Test
    void qrCodeEUmaMatrizQuadradaDeZerosEUns() {
        var qr = totp.qr(totp.uri("pessoa@exemplo.com.br", totp.novoSegredo()));
        assertThat(qr).isNotEmpty();
        assertThat(qr).allMatch(linha -> linha.length() == qr.size() && linha.matches("[01]+"));
        assertThat(totp.uri("pessoa@exemplo.com.br", "ABC")).startsWith("otpauth://totp/Kawashi%3Apessoa%40exemplo.com.br?secret=ABC&issuer=Kawashi");
    }
}
