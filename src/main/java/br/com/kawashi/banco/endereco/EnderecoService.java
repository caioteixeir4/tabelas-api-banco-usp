package br.com.kawashi.banco.endereco;

import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.auxiliar.Dono;
import br.com.kawashi.banco.auxiliar.Donos;
import br.com.kawashi.banco.compartilhado.Documentos;
import br.com.kawashi.banco.compartilhado.Textos;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;
import br.com.kawashi.banco.log.RegistroDeAlteracoes;

/**
 * Regras de negocio de Endereco (tabela auxiliar de filial e de pessoa). O banco garante tipo,
 * tamanho, formato e que o dono existe; o que e REGRA mora aqui: so dono ativo recebe endereco novo,
 * o primeiro endereco e o principal, e um dono com enderecos tem sempre exatamente UM principal.
 */
@Service
@Transactional
public class EnderecoService {

    static final String TABELA = "ENDERECO";

    private static final String PROMOVA_OUTRO = "promova outro endereço a principal antes.";

    private final EnderecoRepository repositorio;
    private final Donos donos;
    private final RegistroDeAlteracoes log;

    public EnderecoService(EnderecoRepository repositorio, Donos donos, RegistroDeAlteracoes log) {
        this.repositorio = repositorio;
        this.donos = donos;
        this.log = log;
    }

    public Endereco criar(Dono dono, EnderecoRequest dados) {
        donos.travarAtivo(dono, "endereço");
        // O primeiro endereco do dono e sempre o principal, mesmo que o pedido diga o contrario.
        boolean principal = repositorio.count(dono.<Endereco>filtro()) == 0 || Boolean.TRUE.equals(dados.principal());
        if (principal) {
            rebaixarPrincipais(dono, null);
        }
        Endereco criado = repositorio.save(new Endereco(dono,
                dados.logradouro(),
                Textos.limpar(dados.numero()),
                Textos.limpar(dados.complemento()),
                Textos.limpar(dados.bairro()),
                dados.cidade(),
                dados.uf().toUpperCase(Locale.ROOT),
                Documentos.somenteDigitos(dados.cep()),
                principal));
        log.criou(TABELA, criado.getId());
        return criado;
    }

    @Transactional(readOnly = true)
    public Endereco buscar(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Endereço", id));
    }

    @Transactional(readOnly = true)
    public Page<Endereco> listar(Dono dono, Pageable paginacao) {
        donos.exigirQueExiste(dono);
        return repositorio.findAll(dono.<Endereco>filtro(), paginacao);
    }

    public Endereco atualizar(Long id, EnderecoRequest dados) {
        Endereco endereco = buscar(id);
        Dono dono = endereco.dono();
        donos.travar(dono);
        boolean principal = Boolean.TRUE.equals(dados.principal());
        if (endereco.isPrincipal() && !principal) {
            // Como ha sempre um unico principal, se este deixar de ser, o dono ficaria sem nenhum.
            throw new RegraDeNegocioException(
                    "O endereço principal não pode deixar de ser principal sem que outro assuma: " + PROMOVA_OUTRO);
        }
        if (principal && !endereco.isPrincipal()) {
            rebaixarPrincipais(dono, id);
        }
        endereco.atualizar(
                dados.logradouro(),
                Textos.limpar(dados.numero()),
                Textos.limpar(dados.complemento()),
                Textos.limpar(dados.bairro()),
                dados.cidade(),
                dados.uf().toUpperCase(Locale.ROOT),
                Documentos.somenteDigitos(dados.cep()),
                principal);
        log.alterou(TABELA, id, "dados do endereço");
        return endereco;
    }

    /** O principal so sai quando e o unico do dono; havendo outros, antes e preciso promover um deles. */
    public void excluir(Long id) {
        Endereco endereco = buscar(id);
        Dono dono = endereco.dono();
        donos.travar(dono);
        if (endereco.isPrincipal() && repositorio.count(dono.<Endereco>filtro()) > 1) {
            throw new RegraDeNegocioException(
                    "O endereço principal não pode ser excluído enquanto houver outros endereços: " + PROMOVA_OUTRO);
        }
        repositorio.delete(endereco);
        repositorio.flush();
        log.apagou(TABELA, id);
    }

    private void rebaixarPrincipais(Dono dono, Long excetoId) {
        Specification<Endereco> principais = dono.<Endereco>filtro()
                .and((raiz, consulta, cb) -> cb.isTrue(raiz.get("principal")));
        for (Endereco atual : repositorio.findAll(principais)) {
            if (!atual.getId().equals(excetoId)) {
                atual.rebaixar();
            }
        }
    }
}
