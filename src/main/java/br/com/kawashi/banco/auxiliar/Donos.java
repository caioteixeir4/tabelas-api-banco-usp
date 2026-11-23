package br.com.kawashi.banco.auxiliar;

import br.com.kawashi.banco.autorizacao.Autorizacao;
import br.com.kawashi.banco.autorizacao.Permissoes;

import org.springframework.stereotype.Component;

import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;
import br.com.kawashi.banco.filial.FilialRepository;
import br.com.kawashi.banco.pessoa.PessoaRepository;

/**
 * O que contato e endereco precisam saber do dono: se existe, se esta ativo, e a trava na linha
 * dele. A trava serializa as alteracoes dos contatos (ou enderecos) de um mesmo dono: o banco nao
 * tem como impedir dois "principais", entao sem ela dois pedidos simultaneos poderiam gravar dois.
 */
@Component
public class Donos {

    private final FilialRepository filiais;
    private final PessoaRepository pessoas;
    private final Autorizacao autorizacao;

    public Donos(FilialRepository filiais, PessoaRepository pessoas, Autorizacao autorizacao) {
        this.filiais = filiais;
        this.pessoas = pessoas;
        this.autorizacao = autorizacao;
    }

    /**
     * Leitura: o dono precisa existir e ser da empresa da sessao (404), e o perfil precisa ler esse
     * tipo de dono (FILIAL_LER ou PESSOA_LER; quem gerencia tambem le).
     */
    public void exigirQueExiste(Dono dono) {
        Long empresa = dono.tipo() == Dono.Tipo.FILIAL
                ? filiais.findById(dono.id()).map(f -> f.getEmpresaId()).orElseThrow(() -> naoEncontrado(dono))
                : pessoas.findById(dono.id()).map(p -> p.getEmpresaId()).orElseThrow(() -> naoEncontrado(dono));
        conferir(dono, empresa, false);
    }

    /**
     * Escrita: trava a linha do dono ate o fim da transacao, confere a empresa e a permissao de
     * gerenciar (FILIAL_GERENCIAR ou PESSOA_GERENCIAR). Devolve se o dono esta ativo.
     */
    public boolean travar(Dono dono) {
        if (dono.tipo() == Dono.Tipo.FILIAL) {
            var filial = filiais.buscarTravando(dono.id()).orElseThrow(() -> naoEncontrado(dono));
            conferir(dono, filial.getEmpresaId(), true);
            return filial.isAtiva();
        }
        var pessoa = pessoas.buscarTravando(dono.id()).orElseThrow(() -> naoEncontrado(dono));
        conferir(dono, pessoa.getEmpresaId(), true);
        return pessoa.isAtiva();
    }

    private void conferir(Dono dono, Long empresaDoDono, boolean escrita) {
        Autorizacao.conferirEmpresa(empresaDoDono, dono.tipo() == Dono.Tipo.FILIAL ? "Filial" : "Pessoa", dono.id());
        boolean filial = dono.tipo() == Dono.Tipo.FILIAL;
        String gerenciar = filial ? Permissoes.FILIAL_GERENCIAR : Permissoes.PESSOA_GERENCIAR;
        if (escrita) {
            autorizacao.exigir(gerenciar);
        } else {
            autorizacao.exigir(filial ? Permissoes.FILIAL_LER : Permissoes.PESSOA_LER, gerenciar);
        }
    }

    /** Criacao: alem de travar, exige dono ativo (422). */
    public void travarAtivo(Dono dono, String oQue) {
        if (!travar(dono)) {
            throw new RegraDeNegocioException("Não é possível cadastrar " + oQue + " para " + dono.descricao()
                    + ", que está inativa. Reative antes.");
        }
    }

    private static RecursoNaoEncontradoException naoEncontrado(Dono dono) {
        return new RecursoNaoEncontradoException(dono.tipo() == Dono.Tipo.FILIAL ? "Filial" : "Pessoa", dono.id());
    }
}
