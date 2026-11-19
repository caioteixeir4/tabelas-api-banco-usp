package br.com.kawashi.banco.login;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Cria um usuario do login e encerra a aplicacao. Usado pelo scripts/criar-usuario.ps1, que e o
 * unico jeito de criar usuario enquanto nao existe a autorizacao (criar usuario sera coisa de
 * administrador, pela API). So liga com {@code app.criar-usuario.pessoa-id}.
 *
 * <p>A senha NUNCA vem por argumento ou propriedade (ficaria no historico do terminal e na lista de
 * processos): vem da variavel de ambiente {@code CRIAR_USUARIO_SENHA}, que o script preenche so para
 * o processo filho. O usuario nasce com a senha VENCIDA: o primeiro login so serve para troca-la.
 */
@Component
@ConditionalOnProperty("app.criar-usuario.pessoa-id")
public class CriarUsuarioNaSubida implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CriarUsuarioNaSubida.class);

    private final Usuarios usuarios;
    private final ConfigurableApplicationContext contexto;

    public CriarUsuarioNaSubida(Usuarios usuarios, ConfigurableApplicationContext contexto) {
        this.usuarios = usuarios;
        this.contexto = contexto;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        String pessoa = contexto.getEnvironment().getRequiredProperty("app.criar-usuario.pessoa-id");
        String email = contexto.getEnvironment().getRequiredProperty("app.criar-usuario.email");
        String senha = System.getenv("CRIAR_USUARIO_SENHA");
        int codigo = 0;
        try {
            if (senha == null || senha.isBlank()) {
                throw new IllegalStateException("Falta a variavel de ambiente CRIAR_USUARIO_SENHA.");
            }
            long id = usuarios.criar(Long.parseLong(pessoa.strip()), email, senha.toCharArray(), true);
            // A linha abaixo e lida pelo script: nao muda o formato sem mudar la.
            LOG.info("USUARIO_CRIADO id={} pessoa={}: a senha temporaria precisa ser trocada no primeiro login.", id, pessoa);
        } catch (RuntimeException ex) {
            LOG.error("USUARIO_NAO_CRIADO: {}", ex.getMessage());
            codigo = 1;
        }
        int saida = codigo;
        System.exit(org.springframework.boot.SpringApplication.exit(contexto, () -> saida));
    }
}
