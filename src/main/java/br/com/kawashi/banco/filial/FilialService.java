package br.com.kawashi.banco.filial;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.Documentos;
import br.com.kawashi.banco.compartilhado.erros.ConflitoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;
import br.com.kawashi.banco.empresa.Empresa;
import br.com.kawashi.banco.empresa.EmpresaRepository;

/**
 * Regras de negocio de Filial. O banco garante formato, tamanho e unicidade do CNPJ; o que e REGRA
 * mora aqui: so se cria filial para empresa ativa, o CNPJ da filial compartilha a raiz da empresa,
 * a ordem 0001 e da matriz, o CNPJ nao muda e inativar/reativar so troca a situacao.
 */
@Service
@Transactional
public class FilialService {

    /** Ordem (digitos 9 a 12 do CNPJ) reservada a matriz. */
    private static final String ORDEM_DA_MATRIZ = "0001";

    private final FilialRepository repositorio;
    private final EmpresaRepository empresas;

    public FilialService(FilialRepository repositorio, EmpresaRepository empresas) {
        this.repositorio = repositorio;
        this.empresas = empresas;
    }

    public Filial criar(Long empresaId, FilialRequest dados) {
        Empresa empresa = buscarEmpresa(empresaId);
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
        return repositorio.save(new Filial(empresa, cnpj, dados.nome().trim()));
    }

    @Transactional(readOnly = true)
    public Filial buscar(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Filial", id));
    }

    @Transactional(readOnly = true)
    public Page<Filial> listar(Long empresaId, Pageable paginacao) {
        buscarEmpresa(empresaId);
        return repositorio.findByEmpresaId(empresaId, paginacao);
    }

    public Filial atualizar(Long id, FilialRequest dados) {
        Filial filial = buscar(id);
        if (!filial.getCnpj().equals(Documentos.somenteDigitos(dados.cnpj()))) {
            throw new RegraDeNegocioException("O CNPJ de uma filial não pode ser alterado depois do cadastro. Só o nome pode mudar.");
        }
        filial.atualizar(dados.nome().trim());
        return filial;
    }

    public Filial inativar(Long id) {
        Filial filial = buscar(id);
        filial.inativar();
        return filial;
    }

    public Filial reativar(Long id) {
        Filial filial = buscar(id);
        filial.reativar();
        return filial;
    }

    /** Nenhuma tabela depende de FILIAL, entao excluir e sempre permitido. */
    public void excluir(Long id) {
        repositorio.delete(buscar(id));
    }

    private Empresa buscarEmpresa(Long empresaId) {
        return empresas.findById(empresaId).orElseThrow(() -> new RecursoNaoEncontradoException("Empresa", empresaId));
    }
}
