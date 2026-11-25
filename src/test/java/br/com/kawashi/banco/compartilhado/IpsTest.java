package br.com.kawashi.banco.compartilhado;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** O grupo de um IP: IPv4 inteiro, IPv6 pelo bloco /64, IPv4 dentro de IPv6 vira IPv4. Sem banco. */
class IpsTest {

    @Test
    void ipv4FicaComoEsta() {
        assertThat(Ips.grupo("203.0.113.5")).isEqualTo("203.0.113.5");
    }

    @Test
    void ipv6DoMesmoBlocoCaiNoMesmoGrupo() {
        assertThat(Ips.grupo("2001:db8:1:2::1")).isEqualTo("2001:db8:1:2::/64");
        assertThat(Ips.grupo("2001:0DB8:0001:0002:ffff:eeee:dddd:cccc")).isEqualTo("2001:db8:1:2::/64");
        assertThat(Ips.grupo("2001:db8:1:3::1")).isEqualTo("2001:db8:1:3::/64");
        assertThat(Ips.grupo("fe80::1%eth0")).isEqualTo("fe80:0:0:0::/64");
    }

    @Test
    void ipv4DentroDeIpv6ViraIpv4ETextoQueNaoEIpVoltaComoVeio() {
        assertThat(Ips.grupo("::ffff:203.0.113.5")).isEqualTo("203.0.113.5");
        assertThat(Ips.grupo("exemplo.com")).isEqualTo("exemplo.com");  // nunca consulta DNS
        assertThat(Ips.grupo(null)).isNull();
    }
}
