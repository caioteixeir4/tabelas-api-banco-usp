package br.com.kawashi.banco.compartilhado;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import br.com.kawashi.banco.compartilhado.erros.ParametroInvalidoException;

/**
 * Cuidados com a paginacao pedida por quem chama a API. O Spring limita o tamanho da pagina
 * ({@code size}), mas nada limita o numero dela ({@code page}), e dois problemas aparecem:
 * <ul>
 *   <li>{@code page} enorme faz {@code page * size} passar do maior {@code int}, que e o que o
 *       Hibernate aceita como deslocamento; o resultado era um erro 500.</li>
 *   <li>ordenar por um campo que nao e unico (razao social, por exemplo) nao tem ordem definida
 *       entre os empatados, e uma mesma linha podia aparecer em duas paginas ou em nenhuma.</li>
 * </ul>
 */
public final class Paginas {

    private Paginas() {
    }

    /**
     * Recusa {@code page} fora do limite (400) e acrescenta o {@code id} como ultimo criterio de
     * ordenacao, para a ordem ser sempre total e a paginacao estavel.
     */
    public static Pageable seguro(Pageable paginacao) {
        if (paginacao.getOffset() > Integer.MAX_VALUE) {
            throw new ParametroInvalidoException("O parâmetro 'page' está acima do limite aceito.");
        }
        Sort ordem = paginacao.getSort();
        if (ordem.getOrderFor("id") == null) {
            ordem = ordem.and(Sort.by("id"));
        }
        return PageRequest.of(paginacao.getPageNumber(), paginacao.getPageSize(), ordem);
    }
}
