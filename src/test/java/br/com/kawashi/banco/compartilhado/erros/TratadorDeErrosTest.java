package br.com.kawashi.banco.compartilhado.erros;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Teste de unidade do tratamento de conflito de concorrencia. Provocar a corrida de verdade (dois
 * DELETE do mesmo registro ao mesmo tempo) exigiria threads e dependeria de timing; aqui se confirma
 * a parte que importa: o erro que o Hibernate/Spring levantam nesse caso vira 409, nao 500.
 */
class TratadorDeErrosTest {

    private final TratadorDeErros tratador = new TratadorDeErros();

    @Test
    void conflitoDeConcorrenciaViraConflito409() {
        OptimisticLockingFailureException erro = new ObjectOptimisticLockingFailureException(Object.class, 7L);

        ProblemDetail problema = tratador.alteradoPorOutroPedido(erro);

        assertThat(problema.getStatus()).isEqualTo(409);
        assertThat(problema.getTitle()).isEqualTo("Conflito");
        assertThat(problema.getDetail()).contains("outro pedido");
    }

    @Test
    void parametroInvalidoViraBadRequest400() {
        ProblemDetail problema = tratador.requisicaoInvalida(new ParametroInvalidoException("page fora do limite"));

        assertThat(problema.getStatus()).isEqualTo(400);
        assertThat(problema.getDetail()).isEqualTo("page fora do limite");
    }
}
