package br.com.kawashi.banco.login;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.MaliciousCounterValueException;

/**
 * Passkeys (WebAuthn): o que vai para o navegador e o que volta dele. Toda a criptografia da
 * verificacao e da biblioteca WebAuthn4J; aqui ficam as regras e o banco.
 *
 * <p>Regras de cada cerimonia:
 * <ul>
 *   <li><b>desafio</b> de uso unico, criado pelo servidor ({@link Desafios}): resposta reenviada
 *       nao serve;</li>
 *   <li><b>origem e dominio</b> conferidos ({@code app.webauthn.origens} e {@code rp-id}): uma
 *       passkey criada para este site nao assina para um site falso, e um site falso nao consegue
 *       pedir a assinatura (e o que torna passkey imune a phishing);</li>
 *   <li><b>verificacao do usuario obrigatoria</b> (digital, rosto ou PIN do aparelho): ter o
 *       aparelho na mao nao basta;</li>
 *   <li><b>passkey residente</b> (o aparelho guarda a conta): o login nao pede nem o e-mail;</li>
 *   <li>atestado "none": nao se pede ao fabricante prova do modelo do aparelho (privacidade; e o que
 *       os provedores de passkey sincronizada entregam);</li>
 *   <li><b>contador</b>: se um aparelho mandar um contador menor que o ultimo visto, a passkey pode
 *       ter sido clonada; o login e recusado e registrado.</li>
 * </ul>
 * No banco fica so a chave PUBLICA (e o identificador da credencial). A chave privada nunca sai do
 * aparelho.
 */
@Component
public class Passkeys {

    /** ES256 (-7) e o que quase todo aparelho usa; RS256 (-257) cobre o Windows Hello mais antigo. */
    private static final List<PublicKeyCredentialParameters> ALGORITMOS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));
    private static final int TEMPO_DA_CERIMONIA_MS = 120_000;

    /** O que o servidor guarda de uma passkey. */
    public record Passkey(long id, long usuarioId, String handle, boolean usuarioAtivo, String dadosDaCredencial,
            long contador, boolean uvInicializado, boolean backupElegivel, boolean backupFeito, String transportes) {
    }

    /** Passkey recem-verificada no cadastro, pronta para gravar. */
    public record Cadastro(String credencialId, String dadosDaCredencial, long contador, boolean uvInicializado,
            boolean backupElegivel, boolean backupFeito, String transportes, String desafio) {
    }

    /** Login verificado: qual passkey e o contador novo. */
    public record Login(long passkeyId, long usuarioId, long contador, boolean backupFeito) {
    }

    /** Uma resposta de passkey que nao passou: o motivo vai so para o log, nunca para o cliente. */
    public static class Recusa extends Exception {
        private final String motivo;
        private final Long usuarioId;

        Recusa(String motivo, Long usuarioId) {
            super(motivo);
            this.motivo = motivo;
            this.usuarioId = usuarioId;
        }

        public String motivo() {
            return motivo;
        }

        public Long usuarioId() {
            return usuarioId;
        }
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;
    private final String rpId;
    private final String rpNome;
    private final Set<Origin> origens;
    private final WebAuthnManager webAuthn = WebAuthnManager.createNonStrictWebAuthnManager();
    private final AttestedCredentialDataConverter conversor = new AttestedCredentialDataConverter(new ObjectConverter());

    public Passkeys(JdbcTemplate jdbc, SchemasDoLogin schemas, @Value("${app.webauthn.rp-id:localhost}") String rpId,
            @Value("${app.webauthn.rp-nome:Kawashi}") String rpNome,
            @Value("${app.webauthn.origens:http://localhost:9090}") String origens) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.rpId = rpId.strip();
        this.rpNome = rpNome.strip();
        this.origens = Arrays.stream(origens.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(Origin::new).collect(Collectors.toCollection(LinkedHashSet::new));
        if (this.origens.isEmpty()) {
            throw new IllegalStateException("app.webauthn.origens vazio: diga de qual endereco o site e servido.");
        }
    }

    // ---- o que vai para o navegador --------------------------------------------------------

    /** PublicKeyCredentialCreationOptionsJSON (WebAuthn nivel 3), pronto para o navegador. */
    public Map<String, Object> opcoesDeCadastro(String desafio, String handle, String email, List<String> jaCadastradas) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("challenge", desafio);
        o.put("rp", Map.of("id", rpId, "name", rpNome));
        o.put("user", Map.of("id", handle, "name", email, "displayName", email));
        o.put("pubKeyCredParams", ALGORITMOS.stream()
                .map(a -> Map.of("type", "public-key", "alg", a.getAlg().getValue())).toList());
        o.put("timeout", TEMPO_DA_CERIMONIA_MS);
        o.put("excludeCredentials", jaCadastradas.stream().map(id -> Map.of("type", "public-key", "id", id)).toList());
        o.put("authenticatorSelection", Map.of("residentKey", "required", "requireResidentKey", true,
                "userVerification", "required"));
        o.put("attestation", "none");
        return o;
    }

    /** PublicKeyCredentialRequestOptionsJSON. Sem allowCredentials: o aparelho mostra as contas que tem. */
    public Map<String, Object> opcoesDeLogin(String desafio) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("challenge", desafio);
        o.put("rpId", rpId);
        o.put("timeout", TEMPO_DA_CERIMONIA_MS);
        o.put("allowCredentials", List.of());
        o.put("userVerification", "required");
        return o;
    }

    /**
     * Opcoes da CONFIRMACAO (a pessoa ja esta logada e vai fazer algo sensivel): so as passkeys dela
     * servem (allowCredentials), com verificacao obrigatoria (digital, rosto ou PIN).
     */
    public Map<String, Object> opcoesDeConfirmacao(String desafio, List<String> dela) {
        Map<String, Object> o = opcoesDeLogin(desafio);
        o.put("allowCredentials", dela.stream().map(id -> Map.of("type", "public-key", "id", id)).toList());
        return o;
    }

    // ---- o que volta do navegador ----------------------------------------------------------

    /** O desafio que a resposta diz estar respondendo (ainda NAO verificada: so para achar o desafio). */
    public String desafioDoCadastro(String respostaJson) throws Recusa {
        try {
            return b64(webAuthn.parseRegistrationResponseJSON(respostaJson).getCollectedClientData().getChallenge().getValue());
        } catch (RuntimeException ex) {
            throw new Recusa("VERIFICACAO_FALHOU", null);
        }
    }

    public String desafioDoLogin(String respostaJson) throws Recusa {
        try {
            return b64(webAuthn.parseAuthenticationResponseJSON(respostaJson).getCollectedClientData().getChallenge().getValue());
        } catch (RuntimeException ex) {
            throw new Recusa("VERIFICACAO_FALHOU", null);
        }
    }

    /** Verifica o cadastro de uma passkey contra o desafio que o servidor criou. */
    public Cadastro verificarCadastro(String respostaJson, String desafio) throws Recusa {
        RegistrationData dados;
        try {
            dados = webAuthn.verifyRegistrationResponseJSON(respostaJson,
                    new RegistrationParameters(propriedades(desafio), ALGORITMOS, true, true));
        } catch (RuntimeException ex) { // inclui VerificationException, que e RuntimeException
            throw new Recusa("VERIFICACAO_FALHOU", null);
        }
        AuthenticatorData<?> autenticador = dados.getAttestationObject().getAuthenticatorData();
        AttestedCredentialData credencial = autenticador.getAttestedCredentialData();
        String transportes = dados.getTransports() == null ? null
                : dados.getTransports().stream().map(AuthenticatorTransport::getValue).sorted().collect(Collectors.joining(","));
        return new Cadastro(b64(credencial.getCredentialId()), Base64.getEncoder().encodeToString(conversor.convert(credencial)),
                autenticador.getSignCount(), autenticador.isFlagUV(), autenticador.isFlagBE(), autenticador.isFlagBS(),
                transportes, desafio);
    }

    /** Verifica um login: a passkey existe, esta ativa, e a assinatura confere com a chave publica guardada. */
    public Login verificarLogin(String respostaJson, String desafio) throws Recusa {
        AuthenticationData dados;
        try {
            dados = webAuthn.parseAuthenticationResponseJSON(respostaJson);
        } catch (RuntimeException ex) {
            throw new Recusa("VERIFICACAO_FALHOU", null);
        }
        Passkey p = buscar(b64(dados.getCredentialId())).orElseThrow(() -> new Recusa("CREDENCIAL_DESCONHECIDA", null));
        if (!p.usuarioAtivo()) {
            throw new Recusa("USUARIO_INATIVO", p.usuarioId());
        }
        // O aparelho diz para qual conta assinou (user handle); tem de ser a dona da passkey.
        if (dados.getUserHandle() != null && !b64(dados.getUserHandle()).equals(p.handle())) {
            throw new Recusa("VERIFICACAO_FALHOU", p.usuarioId());
        }
        CredentialRecordImpl registro = new CredentialRecordImpl(new NoneAttestationStatement(), p.uvInicializado(),
                p.backupElegivel(), p.backupFeito(), p.contador(), conversor.convert(Base64.getDecoder().decode(p.dadosDaCredencial())),
                null, null, null, transportes(p.transportes()));
        try {
            webAuthn.verify(dados, new AuthenticationParameters(propriedades(desafio), registro, null, true, true));
        } catch (MaliciousCounterValueException ex) {
            throw new Recusa("CONTADOR_SUSPEITO", p.usuarioId());
        } catch (RuntimeException ex) { // inclui VerificationException, que e RuntimeException
            throw new Recusa("VERIFICACAO_FALHOU", p.usuarioId());
        }
        AuthenticatorData<?> autenticador = dados.getAuthenticatorData();
        return new Login(p.id(), p.usuarioId(), autenticador.getSignCount(), autenticador.isFlagBS());
    }

    // ---- banco -----------------------------------------------------------------------------

    public long gravar(long usuarioId, Cadastro c, String nome) {
        jdbc.update("INSERT INTO " + schemas.login("PASSKEY") + " (USUARIO_ID, CREDENCIAL_ID, DADOS_CREDENCIAL, CONTADOR,"
                + " UV_INICIALIZADO, BACKUP_ELEGIVEL, BACKUP_FEITO, TRANSPORTES, NOME) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                usuarioId, c.credencialId(), c.dadosDaCredencial(), c.contador(), c.uvInicializado(), c.backupElegivel(),
                c.backupFeito(), c.transportes(), nome);
        return jdbc.queryForObject("SELECT ID FROM " + schemas.login("PASSKEY") + " WHERE CREDENCIAL_ID = ?", Long.class, c.credencialId());
    }

    public boolean credencialJaExiste(String credencialId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("PASSKEY") + " WHERE CREDENCIAL_ID = ?",
                Long.class, credencialId);
        return n != null && n > 0;
    }

    public void registrarUso(Login l) {
        jdbc.update("UPDATE " + schemas.login("PASSKEY") + " SET CONTADOR = ?, BACKUP_FEITO = ?,"
                + " ULTIMO_USO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) WHERE ID = ?", l.contador(), l.backupFeito(), l.passkeyId());
    }

    public List<String> ativasDoUsuario(long usuarioId) {
        return jdbc.queryForList("SELECT CREDENCIAL_ID FROM " + schemas.login("PASSKEY")
                + " WHERE USUARIO_ID = ? AND REVOGADA_EM IS NULL ORDER BY ID", String.class, usuarioId);
    }

    public List<Map<String, Object>> listar(long usuarioId) {
        // As horas estao em UTC no banco; saem com o fuso explicito ("Z"), como no resto do login.
        return jdbc.query("SELECT ID, NOME, CRIADA_EM, ULTIMO_USO_EM, BACKUP_ELEGIVEL FROM " + schemas.login("PASSKEY")
                + " WHERE USUARIO_ID = ? AND REVOGADA_EM IS NULL ORDER BY ID", (r, n) -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("id", r.getLong("ID"));
                    p.put("nome", r.getString("NOME"));
                    p.put("criadaEm", utc(r.getTimestamp("CRIADA_EM")));
                    p.put("ultimoUsoEm", utc(r.getTimestamp("ULTIMO_USO_EM")));
                    p.put("sincronizada", r.getBoolean("BACKUP_ELEGIVEL"));
                    return p;
                }, usuarioId);
    }

    private static java.time.OffsetDateTime utc(java.sql.Timestamp t) {
        return t == null ? null : t.toLocalDateTime().atOffset(java.time.ZoneOffset.UTC);
    }

    /** Revoga uma passkey do proprio usuario. Falso se nao e dele ou ja estava revogada. */
    public boolean revogar(long usuarioId, long passkeyId) {
        return jdbc.update("UPDATE " + schemas.login("PASSKEY") + " SET REVOGADA_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " WHERE ID = ? AND USUARIO_ID = ? AND REVOGADA_EM IS NULL", passkeyId, usuarioId) == 1;
    }

    private Optional<Passkey> buscar(String credencialId) {
        return jdbc.query("SELECT p.ID, p.USUARIO_ID, u.HANDLE, CASE WHEN u.ATIVO THEN 1 ELSE 0 END, p.DADOS_CREDENCIAL,"
                + " p.CONTADOR, CASE WHEN p.UV_INICIALIZADO THEN 1 ELSE 0 END, CASE WHEN p.BACKUP_ELEGIVEL THEN 1 ELSE 0 END,"
                + " CASE WHEN p.BACKUP_FEITO THEN 1 ELSE 0 END, p.TRANSPORTES"
                + " FROM " + schemas.login("PASSKEY") + " p JOIN " + schemas.login("USUARIO") + " u ON u.ID = p.USUARIO_ID"
                + " WHERE p.CREDENCIAL_ID = ? AND p.REVOGADA_EM IS NULL",
                (r, n) -> new Passkey(r.getLong(1), r.getLong(2), r.getString(3), r.getInt(4) == 1,
                        r.getString(5),
                        r.getLong(6), r.getInt(7) == 1, r.getInt(8) == 1, r.getInt(9) == 1, r.getString(10)),
                credencialId).stream().findFirst();
    }

    private ServerProperty propriedades(String desafio) {
        return new ServerProperty(origens, rpId, new DefaultChallenge(Base64.getUrlDecoder().decode(desafio)), null);
    }

    private static Set<AuthenticatorTransport> transportes(String lista) {
        if (lista == null || lista.isBlank()) {
            return null;
        }
        return Arrays.stream(lista.split(",")).map(AuthenticatorTransport::create).collect(Collectors.toSet());
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
