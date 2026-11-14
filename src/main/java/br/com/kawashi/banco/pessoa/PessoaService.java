package br.com.kawashi.banco.pessoa;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

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
import br.com.kawashi.banco.empresa.Empresa;
import br.com.kawashi.banco.empresa.EmpresaRepository;
import br.com.kawashi.banco.log.RegistroDeAlteracoes;

/**
 * Regras de negocio de Pessoa. O banco garante formato, tamanho e unicidade (documento por empresa
 * e tipo); o que e REGRA mora aqui: empresa existente e ativa, documento valido para a natureza,
 * tipo/natureza/documento imutaveis, participacao so para socio (e a soma dos socios de uma empresa
 * nunca passa de 100%), cargo so para colaborador, e pessoa com contatos ou enderecos nao e apagada.
 */
@Service
@Transactional
public class PessoaService {

    static final String TABELA = "PESSOA";

    private static final BigDecimal CEM = new BigDecimal("100.00");

    private final PessoaRepository repositorio;
    private final EmpresaRepository empresas;
    private final JdbcTemplate jdbc;
    private final RegistroDeAlteracoes log;

    public PessoaService(PessoaRepository repositorio, EmpresaRepository empresas, JdbcTemplate jdbc,
            RegistroDeAlteracoes log) {
        this.repositorio = repositorio;
        this.empresas = empresas;
        this.jdbc = jdbc;
        this.log = log;
    }

    public Pessoa criar(Long empresaId, PessoaRequest dados) {
        // A trava na empresa serve a duas regras: "empresa ativa" continua verdade ate o INSERT, e
        // dois socios criados ao mesmo tempo nao passam juntos pela checagem dos 100%.
        Empresa empresa = travarEmpresa(empresaId);
        if (!empresa.isAtiva()) {
            throw new RegraDeNegocioException("A empresa " + empresaId
                    + " está inativa. Só é possível cadastrar pessoas em empresa ativa.");
        }
        TipoDePessoa tipo = TipoDePessoa.valueOf(dados.tipo());
        BigDecimal participacao = participacaoPara(tipo, dados.participacao());
        String cargo = cargoPara(tipo, dados.cargo());
        String documento = Documentos.somenteDigitos(dados.documento());
        if (repositorio.existsByEmpresaIdAndDocumentoAndTipo(empresaId, documento, tipo)) {
            throw new ConflitoException("Já existe " + descrever(tipo) + " com o documento " + documento + " nesta empresa.");
        }
        if (tipo == TipoDePessoa.SOCIO) {
            garantirLimite(somaOuZero(repositorio.somarParticipacoes(empresaId)), participacao);
        }
        Pessoa criada = repositorio.save(
                new Pessoa(empresaId, tipo, dados.natureza(), documento, dados.nome(), participacao, cargo));
        log.criou(TABELA, criada.getId());
        return criada;
    }

    @Transactional(readOnly = true)
    public Pessoa buscar(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Pessoa", id));
    }

    @Transactional(readOnly = true)
    public Page<Pessoa> listar(Long empresaId, String tipo, Boolean ativa, Pageable paginacao) {
        if (!empresas.existsById(empresaId)) {
            throw new RecursoNaoEncontradoException("Empresa", empresaId);
        }
        List<Specification<Pessoa>> filtros = new ArrayList<>();
        filtros.add((raiz, consulta, cb) -> cb.equal(raiz.get("empresaId"), empresaId));
        if (tipo != null && !tipo.isBlank()) {
            TipoDePessoa filtro = tipoDoFiltro(tipo);
            filtros.add((raiz, consulta, cb) -> cb.equal(raiz.get("tipo"), filtro));
        }
        if (ativa != null) {
            filtros.add((raiz, consulta, cb) -> cb.equal(raiz.get("ativa"), ativa));
        }
        return repositorio.findAll(Specification.allOf(filtros), paginacao);
    }

    public Pessoa atualizar(Long id, PessoaRequest dados) {
        Pessoa pessoa = buscar(id);
        if (!pessoa.getTipo().name().equals(dados.tipo())
                || !pessoa.getNatureza().equals(dados.natureza())
                || !pessoa.getDocumento().equals(Documentos.somenteDigitos(dados.documento()))) {
            throw new RegraDeNegocioException("O tipo, a natureza e o documento de uma pessoa não podem ser alterados "
                    + "depois do cadastro. Para outro papel, cadastre a pessoa de novo com o novo tipo.");
        }
        BigDecimal participacao = participacaoPara(pessoa.getTipo(), dados.participacao());
        String cargo = cargoPara(pessoa.getTipo(), dados.cargo());
        if (pessoa.getTipo() == TipoDePessoa.SOCIO) {
            travarEmpresa(pessoa.getEmpresaId());
            // A soma dos DEMAIS socios: o proprio entra com o valor novo, nao com o antigo.
            garantirLimite(somaOuZero(repositorio.somarParticipacoesExceto(pessoa.getEmpresaId(), id)), participacao);
        }
        pessoa.atualizar(dados.nome(), participacao, cargo);
        log.alterou(TABELA, id, "dados cadastrais");
        return pessoa;
    }

    public Pessoa inativar(Long id) {
        Pessoa pessoa = buscar(id);
        if (pessoa.isAtiva()) {
            pessoa.inativar();
            log.alterou(TABELA, id, "inativada");
        }
        return pessoa;
    }

    public Pessoa reativar(Long id) {
        Pessoa pessoa = buscar(id);
        if (!pessoa.isAtiva()) {
            pessoa.reativar();
            log.alterou(TABELA, id, "reativada");
        }
        return pessoa;
    }

    /** Pessoa com contatos ou enderecos nao e apagada (409 dizendo quantos sao): inative. */
    public void excluir(Long id) {
        Pessoa pessoa = buscar(id);
        long contatos = contar("CONTATO", id);
        long enderecos = contar("ENDERECO", id);
        if (contatos + enderecos > 0) {
            throw new ConflitoException("A pessoa possui vínculos (" + contatos + " contato(s), " + enderecos
                    + " endereço(s)) e não pode ser excluída. Inative-a em vez de excluir.");
        }
        repositorio.delete(pessoa);
        repositorio.flush();
        log.apagou(TABELA, id);
    }

    private Empresa travarEmpresa(Long empresaId) {
        return empresas.buscarTravando(empresaId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Empresa", empresaId));
    }

    /** Socio exige participacao; os outros tipos nao podem ter. */
    private static BigDecimal participacaoPara(TipoDePessoa tipo, BigDecimal informada) {
        if (tipo == TipoDePessoa.SOCIO) {
            if (informada == null) {
                throw new RegraDeNegocioException("Sócio precisa da participação (percentual de 0.01 a 100).");
            }
            return informada.setScale(2, RoundingMode.HALF_UP);
        }
        if (informada != null) {
            throw new RegraDeNegocioException("A participação só se aplica a sócio; este cadastro é de "
                    + tipo.name().toLowerCase() + ".");
        }
        return null;
    }

    /** Cargo e opcional e so existe para colaborador. */
    private static String cargoPara(TipoDePessoa tipo, String informado) {
        String cargo = Textos.limpar(informado);
        if (cargo != null && tipo != TipoDePessoa.COLABORADOR) {
            throw new RegraDeNegocioException("O cargo só se aplica a colaborador; este cadastro é de "
                    + tipo.name().toLowerCase() + ".");
        }
        return cargo;
    }

    /** Falha com 422 se a participacao nova, somada a dos outros socios, passar de 100%. */
    private static void garantirLimite(BigDecimal somaDosOutros, BigDecimal participacaoNova) {
        BigDecimal disponivel = CEM.subtract(somaDosOutros);
        // compareTo, nunca equals: 40.0 e 40.00 sao iguais para o negocio mas nao para o equals.
        if (participacaoNova.compareTo(disponivel) > 0) {
            throw new RegraDeNegocioException("A soma das participações dos sócios não pode passar de 100%. "
                    + "Ainda estão disponíveis " + formatar(disponivel.max(BigDecimal.ZERO)) + "% e foram informados "
                    + formatar(participacaoNova) + "%.");
        }
    }

    private static TipoDePessoa tipoDoFiltro(String tipo) {
        try {
            return TipoDePessoa.valueOf(tipo.strip().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new br.com.kawashi.banco.compartilhado.erros.ParametroInvalidoException(
                    "O parâmetro 'tipo' aceita CLIENTE, COLABORADOR, SOCIO ou FORNECEDOR.");
        }
    }

    private static String descrever(TipoDePessoa tipo) {
        return switch (tipo) {
            case CLIENTE -> "um cliente";
            case COLABORADOR -> "um colaborador";
            case SOCIO -> "um sócio";
            case FORNECEDOR -> "um fornecedor";
        };
    }

    private long contar(String tabela, Long pessoaId) {
        // O nome da tabela vem de constantes deste metodo, nunca de entrada do usuario.
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabela + " WHERE PESSOA_ID = ?", Long.class, pessoaId);
        return total == null ? 0 : total;
    }

    private static BigDecimal somaOuZero(BigDecimal soma) {
        return soma == null ? BigDecimal.ZERO : soma;
    }

    private static String formatar(BigDecimal percentual) {
        return percentual.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
