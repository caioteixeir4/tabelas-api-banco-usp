package br.com.kawashi.banco.compartilhado;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * O "grupo" de um IP, usado para contar falhas, bloquear e limitar pedidos:
 * <ul>
 *   <li>IPv4: o proprio endereco;</li>
 *   <li>IPv6: o bloco /64 ({@code 2001:db8:1:2::/64}). Uma conexao comum recebe um bloco /64 inteiro
 *       e pode trocar de endereco dentro dele a vontade; contando pelo endereco exato, quem testa
 *       credenciais escaparia do bloqueio trocando de endereco (achado 11 da revisao de 23/11/2026);</li>
 *   <li>IPv6 que carrega um IPv4 ({@code ::ffff:203.0.113.5}): o IPv4.</li>
 * </ul>
 * O log continua guardando o endereco exato. {@link InetAddress#ofLiteral} so aceita endereco
 * literal: nunca faz consulta de DNS. Texto que nao e IP volta como veio.
 */
public final class Ips {

    private Ips() {
    }

    public static String grupo(String ip) {
        if (ip == null || ip.isBlank()) {
            return ip;
        }
        String semZona = ip.strip();
        int zona = semZona.indexOf('%');
        if (zona >= 0) {
            semZona = semZona.substring(0, zona);
        }
        InetAddress endereco;
        try {
            endereco = InetAddress.ofLiteral(semZona);
        } catch (IllegalArgumentException naoEIp) {
            return ip;
        }
        if (endereco instanceof Inet4Address) {
            return endereco.getHostAddress();
        }
        if (endereco instanceof Inet6Address) {
            byte[] b = endereco.getAddress();
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                s.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff))).append(':');
            }
            return s.append(":/64").toString();
        }
        return ip;
    }
}
