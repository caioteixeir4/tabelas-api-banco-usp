package br.com.kawashi.banco.email;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import br.com.kawashi.banco.login.LogDeSeguranca;
import br.com.kawashi.banco.login.SessaoAtual;
import br.com.kawashi.banco.login.Usuarios;

/**
 * O que o sistema manda por e-mail, sempre para o endereco da PROPRIA conta:
 * <ul>
 *   <li>o convite (primeira passkey, ou passkey nova de quem perdeu o acesso): o segredo vai so para a
 *       pessoa, e quem pediu recebe so o endereco mascarado (achados 1 e 4 da revisao de 23/11/2026);</li>
 *   <li>os avisos de seguranca: passkey cadastrada ou removida, codigos novos, convite usado. Se
 *       nao foi a pessoa, ela fica sabendo;</li>
 *   <li>"ja tem conta": quando uma empresa tenta criar usuario com um e-mail que ja tem conta, a
 *       resposta ao administrador e a mesma de sempre, e quem fica sabendo e o dono do e-mail
 *       (achado 8).</li>
 * </ul>
 * Cada envio vai para o LOG_SEGURANCA (EMAIL_ENVIADO, com o tipo; nunca o endereco nem o texto).
 */
@Component
public class Avisos {

    private final Correio correio;
    private final Usuarios usuarios;
    private final LogDeSeguranca log;
    private final String site;

    public Avisos(Correio correio, Usuarios usuarios, LogDeSeguranca log, @Value("${app.site.url}") String site) {
        this.correio = correio;
        this.usuarios = usuarios;
        this.log = log;
        this.site = site.endsWith("/") ? site.substring(0, site.length() - 1) : site;
    }

    /** Manda o convite para o e-mail da conta. Devolve o endereco mascarado (o que quem pediu pode ver). */
    public String convite(long usuarioId, String segredo, String finalidade, String ip) {
        String email = usuarios.email(usuarioId);
        boolean primeira = "CADASTRO".equals(finalidade);
        String texto = (primeira
                ? "Você recebeu acesso ao sistema Kawashi.\n\nPara entrar, cadastre uma passkey (a digital, o rosto ou o PIN do seu "
                        + "aparelho) neste endereço:\n\n"
                : "Foi pedido um acesso novo à sua conta no sistema Kawashi, para quem perdeu as passkeys e os códigos de "
                        + "recuperação. Se foi você, cadastre uma passkey nova neste endereço:\n\n")
                + site + "/convite.html#" + segredo + "\n\n"
                + "O link vale por pouco tempo e só uma vez. Não o repasse a ninguém: quem o usar entra na sua conta.\n"
                + (primeira ? "" : "Se não foi você quem pediu, avise o administrador da sua empresa: as suas sessões continuam "
                        + "valendo até alguém usar este link.\n");
        correio.enviar(new Correio.Mensagem(email, primeira ? "Seu acesso ao Kawashi" : "Acesso novo à sua conta Kawashi", texto,
                "CONVITE"));
        log.registrar("EMAIL_ENVIADO", usuarioId, "CONVITE " + finalidade, ip, SessaoAtual.quemNoLog());
        return mascarar(email);
    }

    /** Aviso de seguranca para o dono da conta. */
    public void aviso(long usuarioId, String oQueAconteceu, String ip) {
        String texto = "Na sua conta do sistema Kawashi: " + oQueAconteceu + ".\n\n"
                + "Se foi você, não precisa fazer nada. Se NÃO foi, entre e use \"Sair de todos os aparelhos\", remova as passkeys "
                + "que não reconhece e avise o administrador da sua empresa.\n";
        correio.enviar(new Correio.Mensagem(usuarios.email(usuarioId), "Aviso de segurança da sua conta Kawashi", texto, "AVISO"));
        log.registrar("EMAIL_ENVIADO", usuarioId, "AVISO " + oQueAconteceu, ip, SessaoAtual.quemNoLog());
    }

    /**
     * Alguem tentou criar usuario com um e-mail que ja tem conta: o dono do e-mail fica sabendo, quem
     * tentou nao. Devolve o endereco mascarado, como num envio normal.
     */
    public String jaTemConta(long usuarioId, String razaoSocialDaEmpresa, String ip) {
        String email = usuarios.email(usuarioId);
        String texto = "A empresa \"" + razaoSocialDaEmpresa + "\" quis dar a você acesso ao sistema Kawashi, mas este e-mail já tem "
                + "conta. Nada foi mudado na sua conta.\n\nSe você deve ter acesso a essa empresa, peça à equipe da Kawashi para "
                + "ligar a sua conta a ela. Se não conhece essa empresa, pode ignorar este e-mail.\n";
        correio.enviar(new Correio.Mensagem(email, "Pedido de acesso ao Kawashi", texto, "JA_TEM_CONTA"));
        log.registrar("EMAIL_ENVIADO", usuarioId, "JA_TEM_CONTA " + razaoSocialDaEmpresa, ip, SessaoAtual.quemNoLog());
        return mascarar(email);
    }

    /** "joao.silva@exemplo.com.br" vira "j***@exemplo.com.br". */
    public static String mascarar(String email) {
        String e = email.strip().toLowerCase(Locale.ROOT);
        int arroba = e.indexOf('@');
        return arroba <= 0 ? "***" : e.charAt(0) + "***" + e.substring(arroba);
    }
}
