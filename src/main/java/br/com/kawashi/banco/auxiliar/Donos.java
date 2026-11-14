package br.com.kawashi.banco.auxiliar;

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

    public Donos(FilialRepository filiais, PessoaRepository pessoas) {
        this.filiais = filiais;
        this.pessoas = pessoas;
    }

    /** Leitura: o dono do caminho precisa existir (404). */
    public void exigirQueExiste(Dono dono) {
        boolean existe = dono.tipo() == Dono.Tipo.FILIAL ? filiais.existsById(dono.id()) : pessoas.existsById(dono.id());
        if (!existe) {
            throw naoEncontrado(dono);
        }
    }

    /** Escrita: trava a linha do dono ate o fim da transacao. Devolve se ele esta ativo. */
    public boolean travar(Dono dono) {
        if (dono.tipo() == Dono.Tipo.FILIAL) {
            return filiais.buscarTravando(dono.id()).orElseThrow(() -> naoEncontrado(dono)).isAtiva();
        }
        return pessoas.buscarTravando(dono.id()).orElseThrow(() -> naoEncontrado(dono)).isAtiva();
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
