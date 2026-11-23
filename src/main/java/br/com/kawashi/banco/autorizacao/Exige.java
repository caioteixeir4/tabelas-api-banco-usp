package br.com.kawashi.banco.autorizacao;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A regra de acesso de uma rota da API. Toda rota de /api (menos /api/auth) PRECISA de uma destas
 * marcas, senao o {@link FiltroDePermissao} recusa o pedido: esquecer a regra nunca deixa a rota
 * aberta.
 * <ul>
 *   <li>{@code @Exige({"PESSOA_LER"})}: sessao presa a uma empresa e pelo menos uma das permissoes;</li>
 *   <li>{@code @Exige(value = {}, noServico = true)}: sessao presa a uma empresa; a permissao
 *       depende do registro (contato e endereco: a do dono, filial ou pessoa) e e conferida no
 *       servico;</li>
 *   <li>{@code @Exige(value = {}, adminPlataforma = true)}: administrador da plataforma, sem
 *       empresa.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Exige {

    /** Pelo menos uma destas permissoes, na empresa da sessao. */
    String[] value();

    /** A permissao e conferida no servico, pelo registro (ainda exige empresa na sessao). */
    boolean noServico() default false;

    /** So o administrador da plataforma (nao exige empresa). */
    boolean adminPlataforma() default false;
}
