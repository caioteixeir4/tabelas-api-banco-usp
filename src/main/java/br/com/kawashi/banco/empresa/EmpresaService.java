package br.com.kawashi.banco.empresa;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.Documentos;
import br.com.kawashi.banco.compartilhado.Textos;
import br.com.kawashi.banco.compartilhado.erros.ConflitoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;
import br.com.kawashi.banco.log.RegistroDeAlteracoes;

/**
 * Regras de negocio de Empresa. O banco garante formato, tamanho e unicidade; o que e REGRA mora
 * aqui: CNPJ valido, a empresa e sempre a matriz (ordem 0001), o CNPJ nao muda, e empresa com
 * filiais ou pessoas nao e apagada.
 */
@Service
@Transactional
public class EmpresaService {

    static final String TABELA = "EMPRESA";

    /** Ordem do CNPJ (digitos 9 a 12) que identifica a matriz. */
    private static final String ORDEM_MATRIZ = "0001";

    private final EmpresaRepository repositorio;
    private final JdbcTemplate jdbc;
    private final RegistroDeAlteracoes log;

    public EmpresaService(EmpresaRepository repositorio, JdbcTemplate jdbc, RegistroDeAlteracoes log) {
        this.repositorio = repositorio;
        this.jdbc = jdbc;
        this.log = log;
    }

    public Empresa criar(EmpresaRequest dados) {
        String cnpj = Documentos.somenteDigitos(dados.cnpj());
        if (!ORDEM_MATRIZ.equals(Documentos.ordemCnpj(cnpj))) {
            throw new RegraDeNegocioException("A empresa é sempre a matriz, então o CNPJ precisa ter a ordem "
                    + ORDEM_MATRIZ + " (dígitos 9 a 12), mas o informado tem a ordem " + Documentos.ordemCnpj(cnpj)
                    + ". Filiais se cadastram em /api/empresas/{id}/filiais.");
        }
        if (repositorio.existsByCnpj(cnpj)) {
            throw new ConflitoException("Já existe uma empresa com o CNPJ " + cnpj + ".");
        }
        Empresa criada = repositorio.save(new Empresa(cnpj, dados.razaoSocial(), Textos.limpar(dados.nomeFantasia())));
        log.criou(TABELA, criada.getId());
        return criada;
    }

    @Transactional(readOnly = true)
    public Empresa buscar(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Empresa", id));
    }

    @Transactional(readOnly = true)
    public Page<Empresa> listar(String cnpj, String razaoSocial, Boolean ativa, Pageable paginacao) {
        List<Specification<Empresa>> filtros = new ArrayList<>();
        if (cnpj != null && !cnpj.isBlank()) {
            String digitos = Documentos.somenteDigitos(cnpj);
            filtros.add((raiz, consulta, cb) -> cb.equal(raiz.get("cnpj"), digitos));
        }
        if (razaoSocial != null && !razaoSocial.isBlank()) {
            // % e _ digitados pelo usuario sao texto, nao curinga: escapados, senao "?razaoSocial=%"
            // devolvia todas as empresas e "_" casava qualquer razao social.
            String trecho = "%" + Textos.escaparCuringas(Textos.aparar(razaoSocial).toLowerCase(Locale.ROOT)) + "%";
            filtros.add((raiz, consulta, cb) -> cb.like(cb.lower(raiz.get("razaoSocial")), trecho, '\\'));
        }
        if (ativa != null) {
            filtros.add((raiz, consulta, cb) -> cb.equal(raiz.get("ativa"), ativa));
        }
        return repositorio.findAll(Specification.allOf(filtros), paginacao);
    }

    public Empresa atualizar(Long id, EmpresaRequest dados) {
        Empresa empresa = buscar(id);
        if (!empresa.getCnpj().equals(Documentos.somenteDigitos(dados.cnpj()))) {
            throw new RegraDeNegocioException("O CNPJ de uma empresa não pode ser alterado depois do cadastro.");
        }
        empresa.atualizar(dados.razaoSocial(), Textos.limpar(dados.nomeFantasia()));
        log.alterou(TABELA, id, "dados cadastrais");
        return empresa;
    }

    /** Apenas troca a situacao: nao ha cascata para filiais nem pessoas. */
    public Empresa inativar(Long id) {
        Empresa empresa = buscar(id);
        if (empresa.isAtiva()) {
            empresa.inativar();
            log.alterou(TABELA, id, "inativada");
        }
        return empresa;
    }

    public Empresa reativar(Long id) {
        Empresa empresa = buscar(id);
        if (!empresa.isAtiva()) {
            empresa.reativar();
            log.alterou(TABELA, id, "reativada");
        }
        return empresa;
    }

    /**
     * Apaga uma empresa SEM vinculos. Com filiais ou pessoas a resposta e um 409 dizendo quantos
     * sao, e o caminho correto e inativar. A checagem e por SQL direto para esta fatia nao depender
     * das outras: o banco ainda barraria pela chave estrangeira.
     */
    public void excluir(Long id) {
        Empresa empresa = buscar(id);
        long filiais = contar("FILIAL", id);
        long pessoas = contar("PESSOA", id);
        if (filiais + pessoas > 0) {
            throw new ConflitoException("A empresa possui vínculos (" + filiais + " filial(is), " + pessoas
                    + " pessoa(s)) e não pode ser excluída. Inative-a em vez de excluir.");
        }
        repositorio.delete(empresa);
        repositorio.flush();
        log.apagou(TABELA, id);
    }

    private long contar(String tabela, Long empresaId) {
        // O nome da tabela vem de constantes deste metodo, nunca de entrada do usuario.
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabela + " WHERE EMPRESA_ID = ?", Long.class, empresaId);
        return total == null ? 0 : total;
    }
}
