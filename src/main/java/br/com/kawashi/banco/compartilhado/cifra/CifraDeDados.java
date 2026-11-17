package br.com.kawashi.banco.compartilhado.cifra;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifra os dados pessoais ANTES de irem para o banco (CPF/CNPJ de pessoa, e-mail, telefone). Quem
 * le a tabela direto, mesmo com DBA, ve so texto cifrado: a chave fica com a aplicacao, nunca no
 * banco.
 *
 * <p>Duas chaves, de proposito diferentes:
 * <ul>
 *   <li><b>chave de dados</b> (AES-256-GCM): cifra e decifra. Cada gravacao sorteia um numero unico
 *       (IV), entao o mesmo CPF gravado duas vezes gera dois textos cifrados diferentes; quem olha a
 *       tabela nao descobre nem que duas linhas tem o mesmo valor. O GCM tambem autentica: um texto
 *       cifrado alterado, ou copiado de outra coluna, nao decifra.</li>
 *   <li><b>chave de indice</b> (HMAC-SHA256): gera uma "impressao digital" do valor, sempre igual
 *       para o mesmo valor. E ela que permite ao banco garantir unicidade e a API procurar por valor
 *       exato sem decifrar a tabela inteira. Sem a chave, a impressao nao volta ao valor nem pode ser
 *       recalculada por tentativa (um CPF tem poucas combinacoes; um hash sem chave seria quebrado).</li>
 * </ul>
 *
 * <p>O "contexto" (ex.: {@code PESSOA.DOCUMENTO}) entra na autenticacao e na impressao: um valor
 * cifrado para uma coluna nao vale em outra, e o mesmo texto em colunas diferentes tem impressoes
 * diferentes.
 *
 * <p>Formato gravado: {@code v1:} + base64(IV de 12 bytes + texto cifrado + marca de 16 bytes). O
 * prefixo de versao deixa trocar a chave ou o algoritmo no futuro sem ambiguidade.
 */
@Component
public class CifraDeDados {

    static final String VERSAO = "v1:";

    private static final int BYTES_DO_IV = 12;
    private static final int BITS_DA_MARCA = 128;
    private static final int BYTES_DA_CHAVE = 32;

    private final SecretKeySpec chaveDeDados;
    private final SecretKeySpec chaveDeIndice;
    private final SecureRandom sorteio = new SecureRandom();

    public CifraDeDados(@Value("${app.cifra.chave-dados:}") String chaveDeDados,
            @Value("${app.cifra.chave-indice:}") String chaveDeIndice) {
        byte[] dados = decodificar(chaveDeDados, "CIFRA_CHAVE_DADOS");
        byte[] indice = decodificar(chaveDeIndice, "CIFRA_CHAVE_INDICE");
        if (MessageDigest.isEqual(dados, indice)) {
            throw new IllegalStateException("CIFRA_CHAVE_DADOS e CIFRA_CHAVE_INDICE nao podem ser a mesma chave.");
        }
        this.chaveDeDados = new SecretKeySpec(dados, "AES");
        this.chaveDeIndice = new SecretKeySpec(indice, "HmacSHA256");
    }

    /** Cifra um texto para a coluna indicada pelo contexto. Nulo continua nulo. */
    public String cifrar(String contexto, String texto) {
        if (texto == null) {
            return null;
        }
        try {
            byte[] iv = new byte[BYTES_DO_IV];
            sorteio.nextBytes(iv);
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.ENCRYPT_MODE, chaveDeDados, new GCMParameterSpec(BITS_DA_MARCA, iv));
            cifra.updateAAD(contexto.getBytes(StandardCharsets.UTF_8));
            byte[] cifrado = cifra.doFinal(texto.getBytes(StandardCharsets.UTF_8));
            byte[] tudo = ByteBuffer.allocate(iv.length + cifrado.length).put(iv).put(cifrado).array();
            return VERSAO + Base64.getEncoder().encodeToString(tudo);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Falha ao cifrar um dado de " + contexto + ".", ex);
        }
    }

    /**
     * Decifra o que {@link #cifrar} produziu para o MESMO contexto. Texto alterado, de outra coluna
     * ou cifrado com outra chave falha com {@link DadoCifradoInvalidoException}: nunca devolve lixo.
     */
    public String decifrar(String contexto, String cifrado) {
        if (cifrado == null) {
            return null;
        }
        if (!cifrado.startsWith(VERSAO)) {
            throw new DadoCifradoInvalidoException(contexto, "formato desconhecido");
        }
        try {
            byte[] tudo = Base64.getDecoder().decode(cifrado.substring(VERSAO.length()));
            if (tudo.length < BYTES_DO_IV + BITS_DA_MARCA / 8) {
                throw new DadoCifradoInvalidoException(contexto, "curto demais");
            }
            Cipher cifra = Cipher.getInstance("AES/GCM/NoPadding");
            cifra.init(Cipher.DECRYPT_MODE, chaveDeDados, new GCMParameterSpec(BITS_DA_MARCA, tudo, 0, BYTES_DO_IV));
            cifra.updateAAD(contexto.getBytes(StandardCharsets.UTF_8));
            byte[] texto = cifra.doFinal(tudo, BYTES_DO_IV, tudo.length - BYTES_DO_IV);
            return new String(texto, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            // A mensagem nao leva o conteudo nem o motivo criptografico: so a coluna.
            throw new DadoCifradoInvalidoException(contexto, "nao confere com a chave (alterado ou de outra coluna)");
        }
    }

    /**
     * Impressao digital do valor para busca exata e unicidade: 64 caracteres hexadecimais, sempre os
     * mesmos para o mesmo contexto e valor. Quem chama entrega o valor JA normalizado (so digitos,
     * e-mail em minuscula), senao "A@x.com" e "a@x.com" teriam impressoes diferentes.
     */
    public String indice(String contexto, String valor) {
        if (valor == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(chaveDeIndice);
            mac.update(contexto.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Falha ao calcular o indice de " + contexto + ".", ex);
        }
    }

    private static byte[] decodificar(String chave, String variavel) {
        if (chave == null || chave.isBlank()) {
            throw new IllegalStateException("Falta a chave " + variavel + ". Gere as chaves de cifra com "
                    + "scripts/gerar-chaves-de-cifra.ps1 (elas vao para o .env local, nunca para o git).");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(chave.strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(variavel + " precisa estar em base64.");
        }
        if (bytes.length != BYTES_DA_CHAVE) {
            throw new IllegalStateException(variavel + " precisa ter 32 bytes (256 bits); tem " + bytes.length + ".");
        }
        return bytes;
    }
}
