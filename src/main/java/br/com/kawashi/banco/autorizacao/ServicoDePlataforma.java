package br.com.kawashi.banco.autorizacao;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.Cnpj;
import br.com.kawashi.banco.compartilhado.Cpf;
import br.com.kawashi.banco.empresa.Empresa;
import br.com.kawashi.banco.empresa.EmpresaRequest;
import br.com.kawashi.banco.empresa.EmpresaService;
import br.com.kawashi.banco.login.LogDeSeguranca;
import br.com.kawashi.banco.login.SessaoAtual;
import br.com.kawashi.banco.login.Usuarios;
import br.com.kawashi.banco.pessoa.Pessoa;
import br.com.kawashi.banco.pessoa.PessoaRequest;
import br.com.kawashi.banco.pessoa.PessoaService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cria uma empresa nova, de uma vez: a empresa, os perfis padrao (ADMINISTRADOR, OPERADOR,
 * CONSULTA), a pessoa do primeiro administrador (colaborador), o usuario dele, o vinculo como
 * ADMINISTRADOR e o convite. Tudo na mesma transacao: ou nasce inteira, ou nao nasce.
 */
@Service
public class ServicoDePlataforma {

    public record Administrador(
            @NotBlank(message = "Informe o CPF do administrador") @Size(max = 20) @Cpf String cpf,
            @NotBlank(message = "Informe o nome do administrador") @Size(max = 200) String nome,
            @NotBlank(message = "Informe o e-mail do administrador") @Size(max = 150) @Email(message = "E-mail inválido") String email) {
    }

    public record NovaEmpresa(
            @NotBlank(message = "O CNPJ é obrigatório") @Size(max = 30) @Cnpj String cnpj,
            @NotBlank(message = "A razão social é obrigatória") @Size(max = 200) String razaoSocial,
            @Size(max = 200) String nomeFantasia,
            @NotNull(message = "Informe o primeiro administrador") @Valid Administrador administrador) {
    }

    /**
     * O convite do primeiro administrador vai para o e-mail DELE: quem criou a empresa (a plataforma)
     * ve so para onde foi, e nao consegue entrar como ele (achado 4 da revisao de 23/11/2026).
     */
    public record EmpresaCriada(long empresaId, long usuarioId, String enviadoPara) {
    }

    private final EmpresaService empresas;
    private final PessoaService pessoas;
    private final Usuarios usuarios;
    private final Autorizacao autorizacao;
    private final LogDeSeguranca log;
    private final br.com.kawashi.banco.email.Avisos avisos;

    public ServicoDePlataforma(EmpresaService empresas, PessoaService pessoas, Usuarios usuarios, Autorizacao autorizacao,
            LogDeSeguranca log, br.com.kawashi.banco.email.Avisos avisos) {
        this.avisos = avisos;
        this.empresas = empresas;
        this.pessoas = pessoas;
        this.usuarios = usuarios;
        this.autorizacao = autorizacao;
        this.log = log;
    }

    @Transactional
    public EmpresaCriada criarEmpresa(NovaEmpresa dados, String ip) {
        String quem = SessaoAtual.quemNoLog();
        long criador = SessaoAtual.doPedido().map(s -> s.usuarioId()).orElseThrow();
        // O administrador da plataforma nao tem vinculo com a empresa nova: as conferencias de
        // empresa e de permissao ficam desligadas so durante a criacao (ele ja foi conferido).
        return Autorizacao.semConferir(() -> {
            Empresa empresa = empresas.criar(new EmpresaRequest(dados.cnpj(), dados.razaoSocial(), dados.nomeFantasia()));
            long administrador = autorizacao.criarPerfisPadrao(empresa.getId(), quem);
            Pessoa pessoa = pessoas.criar(empresa.getId(), new PessoaRequest("COLABORADOR", "F", dados.administrador().cpf(),
                    dados.administrador().nome(), null, "Administrador"));
            Usuarios.Criado usuario = usuarios.criar(pessoa.getId(), dados.administrador().email(), quem, true);
            autorizacao.vincular(usuario.usuarioId(), empresa.getId(), administrador, quem);
            log.registrar("EMPRESA_CRIADA", criador, "empresa " + empresa.getId() + ", administrador: usuario " + usuario.usuarioId(),
                    ip, quem);
            return new EmpresaCriada(empresa.getId(), usuario.usuarioId(), avisos.convite(usuario.usuarioId(), usuario.convite(), "CADASTRO", ip));
        });
    }
}
