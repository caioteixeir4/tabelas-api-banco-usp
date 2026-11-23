package br.com.kawashi.banco.filial;

import br.com.kawashi.banco.autorizacao.Autorizacao;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.Documentos;
import br.com.kawashi.banco.compartilhado.erros.ConflitoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;
import br.com.kawashi.banco.empresa.Empresa;
import br.com.kawashi.banco.empresa.EmpresaRepository;
import br.com.kawashi.banco.log.RegistroDeAlteracoes;

/**
 * Regras de negocio de Filial. O banco garante formato, tamanho e unicidade do CNPJ; o que e REGRA
 * mora aqui: so se cria filial para empresa ativa, o CNPJ da filial compartilha a raiz da empresa,
 * a ordem 0001 e da matriz, o CNPJ nao muda e filial com contatos ou enderecos nao e apagada.
 */
@Service
@Transactional
public class FilialService {

    static final String TABELA = "FILIAL";

    /** Ordem (digitos 9 a 12 do CNPJ) reservada a matriz. */
    private static final String ORDEM_DA_MATRIZ = "0001";

    private final FilialRepository repositorio;
    private final EmpresaRepository empresas;
    private final JdbcTemplate jdbc;
    private final RegistroDeAlteracoes log;

    public FilialService(FilialRepository repositorio, EmpresaRepository empresas, JdbcTemplate jdbc,
            RegistroDeAlteracoes log) {
        this.repositorio = repositorio;
        this.empresas = empresas;
        this.jdbc = jdbc;
        this.log = log;
    }

    public Filial criar(Long empresaId, FilialRequest dados) {
        Autorizacao.conferirEmpresa(empresaId, "Empresa", empresaId);
        // Trava a linha da empresa ate o fim da transacao: sem isso, um "inativar" concorrente
        // comitava entre a leitura de "ativa" e o INSERT, e a filial nascia numa empresa inativa.
        Empresa empresa = empresas.buscarTravando(empresaId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Empresa", empresaId));
        if (!empresa.isAtiva()) {
            throw new RegraDeNegocioException("A empresa " + empresaId
                    + " está inativa e não pode receber novas filiais. Reative a empresa antes de cadastrar a filial.");
        }
        String cnpj = Documentos.somenteDigitos(dados.cnpj());
        String raizDaEmpresa = Documentos.raizCnpj(empresa.getCnpj());
        String raizDaFilial = Documentos.raizCnpj(cnpj);
        if (!raizDaEmpresa.equals(raizDaFilial)) {
            throw new RegraDeNegocioException("O CNPJ da filial precisa ter a mesma raiz (8 primeiros dígitos) do CNPJ da empresa. "
                    + "Raiz da empresa: " + raizDaEmpresa + "; raiz informada: " + raizDaFilial + ".");
        }
        if (ORDEM_DA_MATRIZ.equals(Documentos.ordemCnpj(cnpj))) {
            throw new RegraDeNegocioException("A ordem " + ORDEM_DA_MATRIZ + " (dígitos 9 a 12 do CNPJ) é da matriz e não pode "
                    + "ser usada em uma filial. Use a ordem 0002 em diante.");
        }
        if (repositorio.existsByCnpj(cnpj)) {
            throw new ConflitoException("Já existe uma filial com o CNPJ " + cnpj + ".");
        }
        Filial criada = repositorio.save(new Filial(empresaId, cnpj, dados.nome()));
        log.criou(TABELA, criada.getId());
        return criada;
    }

    @Transactional(readOnly = true)
    public Filial buscar(Long id) {
        Filial filial = repositorio.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Filial", id));
        Autorizacao.conferirEmpresa(filial.getEmpresaId(), "Filial", id);
        return filial;
    }

    @Transactional(readOnly = true)
    public Page<Filial> listar(Long empresaId, Pageable paginacao) {
        Autorizacao.conferirEmpresa(empresaId, "Empresa", empresaId);
        if (!empresas.existsById(empresaId)) {
            throw new RecursoNaoEncontradoException("Empresa", empresaId);
        }
        return repositorio.findByEmpresaId(empresaId, paginacao);
    }

    public Filial atualizar(Long id, FilialRequest dados) {
        Filial filial = buscar(id);
        if (!filial.getCnpj().equals(Documentos.somenteDigitos(dados.cnpj()))) {
            throw new RegraDeNegocioException("O CNPJ de uma filial não pode ser alterado depois do cadastro. Só o nome pode mudar.");
        }
        filial.atualizar(dados.nome());
        log.alterou(TABELA, id, "dados cadastrais");
        return filial;
    }

    public Filial inativar(Long id) {
        Filial filial = buscar(id);
        if (filial.isAtiva()) {
            filial.inativar();
            log.alterou(TABELA, id, "inativada");
        }
        return filial;
    }

    public Filial reativar(Long id) {
        Filial filial = buscar(id);
        if (!filial.isAtiva()) {
            filial.reativar();
            log.alterou(TABELA, id, "reativada");
        }
        return filial;
    }

    /** Filial com contatos ou enderecos nao e apagada (409 dizendo quantos sao): inative. */
    public void excluir(Long id) {
        Filial filial = buscar(id);
        long contatos = contar("CONTATO", id);
        long enderecos = contar("ENDERECO", id);
        if (contatos + enderecos > 0) {
            throw new ConflitoException("A filial possui vínculos (" + contatos + " contato(s), " + enderecos
                    + " endereço(s)) e não pode ser excluída. Inative-a em vez de excluir.");
        }
        repositorio.delete(filial);
        repositorio.flush();
        log.apagou(TABELA, id);
    }

    private long contar(String tabela, Long filialId) {
        // O nome da tabela vem de constantes deste metodo, nunca de entrada do usuario.
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabela + " WHERE FILIAL_ID = ?", Long.class, filialId);
        return total == null ? 0 : total;
    }
}
