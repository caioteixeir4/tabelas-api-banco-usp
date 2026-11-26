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
                ? "Você recebeu acesso ao sistema Kawashi.\n\nPara entrar, crie uma senha ou cadastre uma passkey (a digital, o rosto "
                        + "ou o PIN do seu aparelho) neste endereço:\n\n"
                : "Foi pedido um acesso novo à sua conta no sistema Kawashi, para quem perdeu as passkeys e os códigos de "
                        + "recuperação. Se foi você, crie uma senha nova ou cadastre uma passkey nova neste endereço:\n\n")
                + site + "/convite.html#" + segredo + "\n\n"
                + "O link vale por pouco tempo e só uma vez. Não o repasse a ninguém: quem o usar entra na sua conta.\n"
                + (primeira ? "" : "Se não foi você quem pediu, avise o administrador da sua empresa: as suas sessões continuam "
                        + "valendo até alguém usar este link.\n");
        correio.enviar(new Correio.Mensagem(email, primeira ? "Seu acesso ao Kawashi" : "Acesso novo à sua conta Kawashi", texto,
                "CONVITE"));
        log.registrar("EMAIL_ENVIADO", usuarioId, "CONVITE " + finalidade, ip, SessaoAtual.quemNoLog());
        return mascarar(email);
    }

    /** "Esqueci minha senha": o link de redefinicao, so para o e-mail da conta. */
    public void redefinicaoDeSenha(long usuarioId, String segredo, int minutos, String ip) {
        String texto = "Foi pedida uma senha nova para a sua conta no sistema Kawashi.\n\nSe foi você, escolha a senha nova neste "
                + "endereço (vale por " + minutos + " minutos e só uma vez):\n\n" + site + "/redefinir.html#" + segredo + "\n\n"
                + "Se a conta tem o app autenticador (2FA), o código dele também vai ser pedido.\n"
                + "Se NÃO foi você, ignore este e-mail: a sua senha continua a mesma. Não repasse o link a ninguém.\n";
        correio.enviar(new Correio.Mensagem(usuarios.email(usuarioId), "Senha nova para a sua conta Kawashi", texto, "REDEFINIR_SENHA"));
        log.registrar("EMAIL_ENVIADO", usuarioId, "REDEFINIR_SENHA", ip, SessaoAtual.quemNoLog());
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
     * Convite de vinculo: uma empresa chamou para trabalhar nela quem ja tem conta. Nada muda ate a
     * pessoa entrar e aceitar. Devolve o endereco mascarado, como num envio normal.
     */
    public String conviteDeVinculo(long usuarioId, String razaoSocialDaEmpresa, String perfil, String convidadoPor, String ip) {
        String email = usuarios.email(usuarioId);
        String texto = convidadoPor + ", da empresa \"" + razaoSocialDaEmpresa + "\", convidou você para trabalhar nela no sistema Kawashi, "
                + "com o cargo " + perfil + ".\n\nNada muda na sua conta até você aceitar. Para aceitar ou recusar, entre no sistema com a "
                + "sua passkey: o convite aparece logo depois de entrar. Ele vale por " + 7 + " dias.\n\n"
                + "Se você não conhece essa empresa, recuse o convite ou simplesmente ignore este e-mail.\n";
        correio.enviar(new Correio.Mensagem(email, "Convite para trabalhar em outra empresa no Kawashi", texto, "CONVITE_VINCULO"));
        log.registrar("EMAIL_ENVIADO", usuarioId, "CONVITE_VINCULO " + razaoSocialDaEmpresa, ip, SessaoAtual.quemNoLog());
        return mascarar(email);
    }

    /**
     * Uma empresa quis dar acesso a um e-mail que ainda nao tem conta: o dono do e-mail fica sabendo
     * (quem tentou nao descobre a diferenca). Devolve o endereco mascarado.
     */
    public String semConta(String email, String razaoSocialDaEmpresa, String ip) {
        String texto = "A empresa \"" + razaoSocialDaEmpresa + "\" quis dar a você acesso ao sistema Kawashi, mas este e-mail ainda não "
                + "tem conta.\n\nSe você deve ter acesso, peça ao administrador dessa empresa para cadastrar você. Se não conhece essa "
                + "empresa, pode ignorar este e-mail.\n";
        correio.enviar(new Correio.Mensagem(email, "Pedido de acesso ao Kawashi", texto, "SEM_CONTA"));
        log.registrar("EMAIL_ENVIADO", null, "SEM_CONTA " + razaoSocialDaEmpresa, ip, SessaoAtual.quemNoLog());
        return mascarar(email);
    }

    /** "joao.silva@exemplo.com.br" vira "j***@exemplo.com.br". */
    public static String mascarar(String email) {
        String e = email.strip().toLowerCase(Locale.ROOT);
        int arroba = e.indexOf('@');
        return arroba <= 0 ? "***" : e.charAt(0) + "***" + e.substring(arroba);
    }
}
