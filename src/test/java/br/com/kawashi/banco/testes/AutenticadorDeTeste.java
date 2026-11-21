package br.com.kawashi.banco.testes;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Um "aparelho" de passkey so para os testes: faz o papel do Windows Hello / celular / chave de
 * seguranca. Gera um par de chaves EC P-256 (ES256), monta as estruturas da especificacao WebAuthn
 * a mao (authenticatorData, attestationObject em CBOR, clientDataJSON) e assina como um autenticador
 * de verdade. Assim a API verifica as respostas com o WebAuthn4J exatamente como faria com um
 * navegador, sem atalho nenhum.
 *
 * <p>Os campos publicos permitem estragar a resposta de proposito (origem errada, contador voltando,
 * outro rp-id) para conferir que a API recusa.
 */
public class AutenticadorDeTeste {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom ALEATORIO = new SecureRandom();

    /** De onde o "navegador" diz estar (tem de ser uma das app.webauthn.origens). */
    public String origem = "http://localhost:9090";
    /** Dominio para o qual a passkey foi criada (o hash dele vai no authenticatorData). */
    public String rpId = "localhost";
    /** Contador de assinaturas: cada login soma 1 (aparelhos sincronizados mandam sempre 0). */
    public long contador = 0;
    /** Se o aparelho conferiu a pessoa (biometria/PIN). A API exige. */
    public boolean verificouAPessoa = true;

    private final KeyPair chaves;
    private final byte[] credencialId;
    private String handleDoUsuario;

    public AutenticadorDeTeste() {
        this(novoId());
    }

    /** Um aparelho com o MESMO id de credencial de outro, mas chave diferente (um clone falso). */
    public AutenticadorDeTeste(byte[] credencialId) {
        try {
            KeyPairGenerator gerador = KeyPairGenerator.getInstance("EC");
            gerador.initialize(new ECGenParameterSpec("secp256r1"));
            this.chaves = gerador.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        this.credencialId = credencialId.clone();
    }

    public byte[] credencialId() {
        return credencialId.clone();
    }

    public String credencialIdB64() {
        return B64.encodeToString(credencialId);
    }

    /**
     * Responde ao navigator.credentials.create(): devolve o JSON de RegistrationResponseJSON (o que o
     * {@code PublicKeyCredential.toJSON()} do navegador produz).
     */
    public String cadastrar(String desafio, String handleDoUsuario) {
        this.handleDoUsuario = handleDoUsuario;
        String clientData = clientData("webauthn.create", desafio);
        byte[] authData = concatenar(cabecalhoDoAuthData(0x40), dadosDaCredencial());
        byte[] attestationObject = new Cbor().mapa(3)
                .texto("fmt").texto("none")
                .texto("attStmt").mapa(0)
                .texto("authData").bytes(authData)
                .pronto();
        return "{\"id\":\"" + credencialIdB64() + "\",\"rawId\":\"" + credencialIdB64() + "\",\"type\":\"public-key\","
                + "\"authenticatorAttachment\":\"platform\",\"clientExtensionResults\":{},"
                + "\"response\":{\"clientDataJSON\":\"" + b64(clientData) + "\",\"attestationObject\":\""
                + B64.encodeToString(attestationObject) + "\",\"transports\":[\"internal\"]"
                // Campos que o toJSON() dos navegadores tambem manda (redundantes com o attestationObject).
                + ",\"authenticatorData\":\"" + B64.encodeToString(authData) + "\",\"publicKey\":\""
                + B64.encodeToString(chaves.getPublic().getEncoded()) + "\",\"publicKeyAlgorithm\":-7}}";
    }

    /** Responde ao navigator.credentials.get(): AuthenticationResponseJSON assinado. */
    public String entrar(String desafio) {
        if (contaAssinaturas) {
            contador++;
        }
        return assinar(desafio);
    }

    /** Assina com o contador atual, sem mexer nele (para simular um clone com contador velho). */
    public String assinar(String desafio) {
        String clientData = clientData("webauthn.get", desafio);
        byte[] authData = cabecalhoDoAuthData(0);
        byte[] assinatura;
        try {
            Signature ecdsa = Signature.getInstance("SHA256withECDSA");
            ecdsa.initSign(chaves.getPrivate());
            ecdsa.update(authData);
            ecdsa.update(MessageDigest.getInstance("SHA-256").digest(clientData.getBytes(StandardCharsets.UTF_8)));
            assinatura = ecdsa.sign();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return "{\"id\":\"" + credencialIdB64() + "\",\"rawId\":\"" + credencialIdB64() + "\",\"type\":\"public-key\","
                + "\"authenticatorAttachment\":\"platform\",\"clientExtensionResults\":{},"
                + "\"response\":{\"clientDataJSON\":\"" + b64(clientData) + "\",\"authenticatorData\":\"" + B64.encodeToString(authData)
                + "\",\"signature\":\"" + B64.encodeToString(assinatura) + "\""
                + (handleDoUsuario == null ? "" : ",\"userHandle\":\"" + handleDoUsuario + "\"") + "}}";
    }

    /** Se o aparelho conta assinaturas (chave de seguranca) ou manda sempre 0 (passkey sincronizada). */
    public boolean contaAssinaturas = true;

    /** Para fingir que este aparelho tem a passkey de outra conta. */
    public void handleDoUsuario(String handle) {
        this.handleDoUsuario = handle;
    }

    // ---- estruturas WebAuthn ---------------------------------------------------------------

    private String clientData(String tipo, String desafio) {
        return "{\"type\":\"" + tipo + "\",\"challenge\":\"" + desafio + "\",\"origin\":\"" + origem + "\",\"crossOrigin\":false}";
    }

    /** rpIdHash (32) | flags (1) | signCount (4). UP sempre; UV se verificou a pessoa. */
    private byte[] cabecalhoDoAuthData(int flagsExtras) {
        int flags = 0x01 | (verificouAPessoa ? 0x04 : 0) | flagsExtras;
        try {
            return ByteBuffer.allocate(37)
                    .put(MessageDigest.getInstance("SHA-256").digest(rpId.getBytes(StandardCharsets.UTF_8)))
                    .put((byte) flags)
                    .putInt((int) contador)
                    .array();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** attestedCredentialData: AAGUID (16 zeros) | tamanho do id (2) | id | chave publica COSE. */
    private byte[] dadosDaCredencial() {
        ECPublicKey publica = (ECPublicKey) chaves.getPublic();
        byte[] coseKey = new Cbor().mapa(5)
                .inteiro(1).inteiro(2)        // kty: EC2
                .inteiro(3).inteiro(-7)       // alg: ES256
                .inteiro(-1).inteiro(1)       // crv: P-256
                .inteiro(-2).bytes(coordenada(publica.getW().getAffineX()))
                .inteiro(-3).bytes(coordenada(publica.getW().getAffineY()))
                .pronto();
        return ByteBuffer.allocate(16 + 2 + credencialId.length + coseKey.length)
                .put(new byte[16])
                .putShort((short) credencialId.length)
                .put(credencialId)
                .put(coseKey)
                .array();
    }

    private static byte[] coordenada(BigInteger valor) {
        byte[] bruto = valor.toByteArray();
        byte[] saida = new byte[32];
        int copiar = Math.min(bruto.length, 32);
        System.arraycopy(bruto, bruto.length - copiar, saida, 32 - copiar, copiar);
        return saida;
    }

    private static byte[] concatenar(byte[] a, byte[] b) {
        return ByteBuffer.allocate(a.length + b.length).put(a).put(b).array();
    }

    private static String b64(String texto) {
        return B64.encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] novoId() {
        byte[] id = new byte[32];
        ALEATORIO.nextBytes(id);
        return id;
    }

    /** O pedaco de CBOR (RFC 8949) que o WebAuthn usa: inteiros, bytes, texto e mapas. */
    private static final class Cbor {
        private final ByteArrayOutputStream saida = new ByteArrayOutputStream();

        Cbor inteiro(long n) {
            return n >= 0 ? cabecalho(0, n) : cabecalho(1, -1 - n);
        }

        Cbor bytes(byte[] b) {
            cabecalho(2, b.length);
            saida.writeBytes(b);
            return this;
        }

        Cbor texto(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            cabecalho(3, b.length);
            saida.writeBytes(b);
            return this;
        }

        Cbor mapa(int pares) {
            return cabecalho(5, pares);
        }

        byte[] pronto() {
            return saida.toByteArray();
        }

        private Cbor cabecalho(int tipo, long n) {
            if (n < 24) {
                saida.write((tipo << 5) | (int) n);
            } else if (n < 256) {
                saida.write((tipo << 5) | 24);
                saida.write((int) n);
            } else if (n < 65536) {
                saida.write((tipo << 5) | 25);
                saida.write((int) (n >> 8));
                saida.write((int) n & 0xff);
            } else {
                throw new IllegalArgumentException("grande demais para este CBOR de teste");
            }
            return this;
        }
    }
}
