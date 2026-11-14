package br.com.kawashi.banco.contato;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.auxiliar.Dono;
import br.com.kawashi.banco.auxiliar.Donos;
import br.com.kawashi.banco.compartilhado.Documentos;
import br.com.kawashi.banco.compartilhado.erros.ConflitoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;
import br.com.kawashi.banco.log.RegistroDeAlteracoes;

/**
 * Regras de negocio de Contato (tabela auxiliar de filial e de pessoa). O banco garante tipo,
 * tamanho e que o dono existe; o que e REGRA mora aqui: so dono ativo recebe contato novo, o valor
 * combina com o tipo, o mesmo valor nao se repete no mesmo dono, o primeiro contato de cada tipo e o
 * principal, e ha um unico principal por dono e por tipo (um e-mail principal, um telefone principal).
 */
@Service
@Transactional
public class ContatoService {

    static final String TABELA = "CONTATO";

    private final ContatoRepository repositorio;
    private final Donos donos;
    private final RegistroDeAlteracoes log;

    public ContatoService(ContatoRepository repositorio, Donos donos, RegistroDeAlteracoes log) {
        this.repositorio = repositorio;
        this.donos = donos;
        this.log = log;
    }

    public Contato criar(Dono dono, ContatoRequest dados) {
        donos.travarAtivo(dono, "contato");
        TipoDeContato tipo = TipoDeContato.valueOf(dados.tipo());
        String valor = normalizar(tipo, dados.valor());
        if (repositorio.count(doDono(dono, tipo).and(comValor(valor))) > 0) {
            throw new ConflitoException("Este contato já está cadastrado para " + dono.descricao() + ".");
        }
        // O primeiro contato de cada tipo e sempre o principal, mesmo que o pedido diga o contrario.
        boolean principal = repositorio.count(doDono(dono, tipo)) == 0 || Boolean.TRUE.equals(dados.principal());
        if (principal) {
            rebaixarPrincipais(dono, tipo, null);
        }
        Contato criado = repositorio.save(new Contato(dono, tipo, valor, principal));
        log.criou(TABELA, criado.getId());
        return criado;
    }

    @Transactional(readOnly = true)
    public Contato buscar(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Contato", id));
    }

    @Transactional(readOnly = true)
    public Page<Contato> listar(Dono dono, Pageable paginacao) {
        donos.exigirQueExiste(dono);
        return repositorio.findAll(dono.<Contato>filtro(), paginacao);
    }

    public Contato atualizar(Long id, ContatoRequest dados) {
        Contato contato = buscar(id);
        Dono dono = contato.dono();
        donos.travar(dono);
        TipoDeContato tipo = contato.getTipo();
        if (!tipo.name().equals(dados.tipo())) {
            throw new RegraDeNegocioException("O tipo de um contato não pode ser alterado. Cadastre outro contato com o novo tipo.");
        }
        String valor = normalizar(tipo, dados.valor());
        if (repositorio.count(doDono(dono, tipo).and(comValor(valor)).and(excetoId(id))) > 0) {
            throw new ConflitoException("Este contato já está cadastrado para " + dono.descricao() + ".");
        }
        boolean principal = Boolean.TRUE.equals(dados.principal());
        if (contato.isPrincipal() && !principal) {
            // Como ha sempre um unico principal, se este deixar de ser, o dono ficaria sem nenhum.
            throw new RegraDeNegocioException("O contato principal não pode deixar de ser principal sem que outro assuma: "
                    + "marque outro contato do mesmo tipo como principal.");
        }
        if (principal && !contato.isPrincipal()) {
            rebaixarPrincipais(dono, tipo, id);
        }
        contato.atualizar(valor, principal);
        log.alterou(TABELA, id, "dados do contato");
        return contato;
    }

    /** O principal so sai quando e o unico do tipo; havendo outros, antes e preciso promover um deles. */
    public void excluir(Long id) {
        Contato contato = buscar(id);
        Dono dono = contato.dono();
        donos.travar(dono);
        if (contato.isPrincipal() && repositorio.count(doDono(dono, contato.getTipo())) > 1) {
            throw new RegraDeNegocioException("O contato principal não pode ser excluído enquanto houver outros do mesmo tipo: "
                    + "marque outro como principal antes.");
        }
        repositorio.delete(contato);
        repositorio.flush();
        log.apagou(TABELA, id);
    }

    private void rebaixarPrincipais(Dono dono, TipoDeContato tipo, Long excetoId) {
        Specification<Contato> principais = doDono(dono, tipo).and((raiz, consulta, cb) -> cb.isTrue(raiz.get("principal")));
        for (Contato atual : repositorio.findAll(principais)) {
            if (!atual.getId().equals(excetoId)) {
                atual.rebaixar();
            }
        }
    }

    private static String normalizar(TipoDeContato tipo, String valor) {
        return tipo == TipoDeContato.TELEFONE ? Documentos.somenteDigitos(valor) : valor;
    }

    private static Specification<Contato> doDono(Dono dono, TipoDeContato tipo) {
        return dono.<Contato>filtro().and((raiz, consulta, cb) -> cb.equal(raiz.get("tipo"), tipo));
    }

    private static Specification<Contato> comValor(String valor) {
        return (raiz, consulta, cb) -> cb.equal(raiz.get("valor"), valor);
    }

    private static Specification<Contato> excetoId(Long id) {
        return (raiz, consulta, cb) -> cb.notEqual(raiz.get("id"), id);
    }
}
