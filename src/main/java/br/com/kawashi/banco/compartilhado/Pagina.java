package br.com.kawashi.banco.compartilhado;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Formato estavel de resposta paginada (o PageImpl do Spring Data nao tem contrato de JSON estavel).
 * Paginas comecam em 0, como no Spring.
 */
public record Pagina<T>(List<T> itens, int pagina, int tamanho, long total, int totalPaginas) {

    public static <E, T> Pagina<T> de(Page<E> pagina, Function<E, T> conversor) {
        return new Pagina<>(
                pagina.getContent().stream().map(conversor).toList(),
                pagina.getNumber(),
                pagina.getSize(),
                pagina.getTotalElements(),
                pagina.getTotalPages());
    }
}
