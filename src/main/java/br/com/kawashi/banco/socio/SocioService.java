package br.com.kawashi.banco.socio;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Regras de negocio de Socio. O banco garante formato, tamanho e unicidade (CPF por empresa); o que
 * e REGRA mora aqui: empresa existente e ativa, CPF normalizado e imutavel, e a soma das
 * participacoes dos socios de uma empresa que nunca passa de 100%.
 */
@Service
@Transactional
public class SocioService {

    private static final BigDecimal CEM = new BigDecimal("100.00");

    private final SocioRepository repositorio;
    private final EmpresaRepository empresas;
    private final EntityManager entityManager;

    public SocioService(SocioRepository repositorio, EmpresaRepository empresas, EntityManager entityManager) {
        this.repositorio = repositorio;
        this.empresas = empresas;
        this.entityManager = entityManager;
    }

    public Socio criar(Long empresaId, SocioRequest dados) {
        Empresa empresa = travarEmpresa(empresaId);
        if (!empresa.isAtiva()) {
            throw new RegraDeNegocioException("A empresa " + empresaId
                    + " está inativa. Só é possível cadastrar sócios em empresa ativa.");
        }
        String cpf = Documentos.somenteDigitos(dados.cpf());
        if (repositorio.existsByEmpresaIdAndCpf(empresaId, cpf)) {
            throw new ConflitoException("Já existe um sócio com o CPF " + cpf + " nesta empresa.");
        }
        BigDecimal participacao = normalizar(dados.participacao());
        garantirLimite(somaOuZero(repositorio.somarParticipacoes(empresaId)), participacao);
        return repositorio.save(new Socio(empresaId, dados.nome().trim(), cpf, participacao));
    }

    @Transactional(readOnly = true)
    public Socio buscar(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Sócio", id));
    }

    @Transactional(readOnly = true)
    public Page<Socio> listar(Long empresaId, Pageable paginacao) {
        if (!empresas.existsById(empresaId)) {
            throw new RecursoNaoEncontradoException("Empresa", empresaId);
        }
        return repositorio.findByEmpresaId(empresaId, paginacao);
    }

    public Socio atualizar(Long id, SocioRequest dados) {
        Socio socio = buscar(id);
        if (!socio.getCpf().equals(Documentos.somenteDigitos(dados.cpf()))) {
            throw new RegraDeNegocioException("O CPF de um sócio não pode ser alterado depois do cadastro.");
        }
        travarEmpresa(socio.getEmpresaId());
        BigDecimal participacao = normalizar(dados.participacao());
        // A soma dos DEMAIS socios: o proprio socio entra com o valor novo, nao com o antigo.
        garantirLimite(somaOuZero(repositorio.somarParticipacoesExceto(socio.getEmpresaId(), id)), participacao);
        socio.atualizar(dados.nome().trim(), participacao);
        return socio;
    }

    /** Excluir e sempre permitido: a participacao do socio volta a ficar disponivel. */
    public void excluir(Long id) {
        repositorio.delete(buscar(id));
    }

    /**
     * Busca a empresa travando a linha ate o fim da transacao. Isso serializa as mudancas de socios
     * da MESMA empresa, para que dois pedidos simultaneos nao passem juntos pela checagem dos 100% e
     * estourem o limite (o banco nao tem como conferir uma soma entre linhas).
     */
    private Empresa travarEmpresa(Long empresaId) {
        Empresa empresa = entityManager.find(Empresa.class, empresaId, LockModeType.PESSIMISTIC_WRITE);
        if (empresa == null) {
            throw new RecursoNaoEncontradoException("Empresa", empresaId);
        }
        return empresa;
    }

    /** Falha com 422 se a participacao nova, somada a dos outros socios, passar de 100%. */
    private void garantirLimite(BigDecimal somaDosOutros, BigDecimal participacaoNova) {
        BigDecimal disponivel = CEM.subtract(somaDosOutros);
        // compareTo, nunca equals: 40.0 e 40.00 sao iguais para o negocio mas nao para o equals.
        if (participacaoNova.compareTo(disponivel) > 0) {
            throw new RegraDeNegocioException("A soma das participações dos sócios não pode passar de 100%. "
                    + "Ainda estão disponíveis " + formatar(disponivel.max(BigDecimal.ZERO)) + "% e foram informados "
                    + formatar(participacaoNova) + "%.");
        }
    }

    /** O Request ja garante no maximo 2 casas; aqui so se fixa a escala para comparar e gravar igual. */
    private static BigDecimal normalizar(BigDecimal participacao) {
        return participacao.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal somaOuZero(BigDecimal soma) {
        return soma == null ? BigDecimal.ZERO : soma;
    }

    private static String formatar(BigDecimal percentual) {
        return percentual.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
