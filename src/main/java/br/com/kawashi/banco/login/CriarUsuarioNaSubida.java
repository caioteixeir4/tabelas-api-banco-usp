package br.com.kawashi.banco.login;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Cria um usuario do login (ou um convite novo para quem perdeu o acesso) e encerra a aplicacao.
 * Usado pelo scripts/criar-usuario.ps1, que e o unico jeito de criar usuario enquanto nao existe a
 * autorizacao (criar usuario sera coisa de administrador, pela API). So liga com
 * {@code app.criar-usuario.modo}.
 *
 * <p>Nao ha senha: o usuario nasce com um CONVITE de uso unico, e e no link dele que a pessoa
 * cadastra a passkey. O segredo do convite NUNCA vai para o log nem para a saida: e gravado no
 * arquivo indicado pela variavel de ambiente {@code CRIAR_USUARIO_ARQUIVO}, que o script le, guarda
 * no Vault e apaga.
 */
@Component
@ConditionalOnProperty("app.criar-usuario.modo")
public class CriarUsuarioNaSubida implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CriarUsuarioNaSubida.class);

    private final Usuarios usuarios;
    private final br.com.kawashi.banco.autorizacao.Autorizacao autorizacao;
    private final ConfigurableApplicationContext contexto;

    public CriarUsuarioNaSubida(Usuarios usuarios, br.com.kawashi.banco.autorizacao.Autorizacao autorizacao,
            ConfigurableApplicationContext contexto) {
        this.usuarios = usuarios;
        this.autorizacao = autorizacao;
        this.contexto = contexto;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        var env = contexto.getEnvironment();
        String modo = env.getRequiredProperty("app.criar-usuario.modo");
        String arquivo = System.getenv("CRIAR_USUARIO_ARQUIVO");
        int codigo = 0;
        try {
            if (arquivo == null || arquivo.isBlank()) {
                throw new IllegalStateException("Falta a variavel de ambiente CRIAR_USUARIO_ARQUIVO.");
            }
            String convite;
            if ("novo".equals(modo)) {
                Usuarios.Criado c = usuarios.criar(Long.parseLong(env.getRequiredProperty("app.criar-usuario.pessoa-id").strip()),
                        env.getRequiredProperty("app.criar-usuario.email"), "SCRIPT", false);  // a equipe entrega (Vault)
                convite = c.convite();
                // A linha abaixo e lida pelo script: nao muda o formato sem mudar la.
                LOG.info("USUARIO_CRIADO id={}: convite gravado no arquivo indicado.", c.usuarioId());
            } else if ("convite".equals(modo)) {
                long usuarioId = Long.parseLong(env.getRequiredProperty("app.criar-usuario.usuario-id").strip());
                convite = usuarios.novoConvite(usuarioId, "SCRIPT", false);  // a equipe entrega (Vault)
                LOG.info("USUARIO_CRIADO id={}: convite novo gravado no arquivo indicado.", usuarioId);
            } else if ("vincular".equals(modo)) {
                // Da a um usuario que ja existe o acesso a uma empresa, com um perfil. Se a empresa
                // ainda nao tem perfis (empresa anterior a autorizacao), cria os padrao.
                long usuarioId = Long.parseLong(env.getRequiredProperty("app.criar-usuario.usuario-id").strip());
                long empresaId = Long.parseLong(env.getRequiredProperty("app.criar-usuario.empresa-id").strip());
                String perfil = env.getRequiredProperty("app.criar-usuario.perfil").strip().toUpperCase(java.util.Locale.ROOT);
                String quem = "SCRIPT:CRIAR_USUARIO";
                if (autorizacao.perfilPorNome(empresaId, "ADMINISTRADOR").isEmpty()) {
                    autorizacao.criarPerfisPadrao(empresaId, quem);
                }
                long perfilId = autorizacao.perfilPorNome(empresaId, perfil)
                        .orElseThrow(() -> new IllegalStateException("A empresa " + empresaId + " nao tem o perfil " + perfil + "."));
                autorizacao.vincular(usuarioId, empresaId, perfilId, quem);
                LOG.info("USUARIO_CRIADO id={}: vinculado a empresa {} como {}.", usuarioId, empresaId, perfil);
                convite = "";
            } else {
                throw new IllegalStateException("app.criar-usuario.modo deve ser 'novo', 'convite' ou 'vincular'.");
            }
            Files.writeString(Path.of(arquivo), convite, StandardCharsets.US_ASCII);
        } catch (Exception ex) {
            LOG.error("USUARIO_NAO_CRIADO: {}", ex.getMessage());
            codigo = 1;
        }
        int saida = codigo;
        System.exit(SpringApplication.exit(contexto, () -> saida));
    }
}
