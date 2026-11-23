package br.com.kawashi.banco.compartilhado.erros;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.validation.ConstraintViolationException;

/**
 * Traduz excecoes em respostas de erro no formato RFC 9457 (application/problem+json), sempre com
 * a mesma cara: status, titulo, detalhe legivel e, nas validacoes, a lista de campos com problema.
 *
 * <p>Estende ResponseEntityExceptionHandler de proposito: assim os erros do proprio Spring (rota
 * inexistente, metodo nao permitido, media type errado...) tambem saem neste formato, e o Boot nao
 * registra o tratador padrao dele por cima deste.
 */
@RestControllerAdvice
public class TratadorDeErros extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TratadorDeErros.class);

    public record ErroDeCampo(String campo, String mensagem) {
    }

    // ---- erros de negocio da propria API ----------------------------------------

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    ProblemDetail naoEncontrado(RecursoNaoEncontradoException ex) {
        return problema(HttpStatus.NOT_FOUND, "Não encontrado", ex.getMessage());
    }

    @ExceptionHandler(ConflitoException.class)
    ProblemDetail conflito(ConflitoException ex) {
        return problema(HttpStatus.CONFLICT, "Conflito", ex.getMessage());
    }

    @ExceptionHandler(RegraDeNegocioException.class)
    ProblemDetail regraDeNegocio(RegraDeNegocioException ex) {
        return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Regra de negócio violada", ex.getMessage());
    }

    @ExceptionHandler(ParametroInvalidoException.class)
    ProblemDetail requisicaoInvalida(ParametroInvalidoException ex) {
        return problema(HttpStatus.BAD_REQUEST, "Parâmetro inválido", ex.getMessage());
    }

    /**
     * Dois pedidos mexendo no mesmo registro ao mesmo tempo (por exemplo, o mesmo DELETE enviado
     * duas vezes): o segundo nao encontra mais a linha. Nao e erro do servidor, e um conflito.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail alteradoPorOutroPedido(OptimisticLockingFailureException ex) {
        LOG.info("Conflito de concorrencia: {}", ex.getMessage());
        return problema(HttpStatus.CONFLICT, "Conflito",
                "O registro foi alterado ou excluído por outro pedido. Consulte-o de novo e repita a operação.");
    }

    // ---- validacao de entrada ---------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErroDeCampo> erros = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ErroDeCampo(e.getField(), e.getDefaultMessage()))
                .toList();
        ProblemDetail problema = problema(HttpStatus.BAD_REQUEST, "Dados inválidos",
                "Um ou mais campos não passaram na validação.");
        problema.setProperty("erros", erros);
        return handleExceptionInternal(ex, problema, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErroDeCampo> erros = ex.getParameterValidationResults().stream()
                .flatMap(resultado -> resultado.getResolvableErrors().stream()
                        .map(e -> new ErroDeCampo(resultado.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .toList();
        ProblemDetail problema = problema(HttpStatus.BAD_REQUEST, "Dados inválidos",
                "Um ou mais parâmetros não passaram na validação.");
        problema.setProperty("erros", erros);
        return handleExceptionInternal(ex, problema, headers, status, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail parametroInvalido(ConstraintViolationException ex) {
        List<ErroDeCampo> erros = ex.getConstraintViolations().stream()
                .map(v -> new ErroDeCampo(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        ProblemDetail problema = problema(HttpStatus.BAD_REQUEST, "Dados inválidos",
                "Um ou mais parâmetros não passaram na validação.");
        problema.setProperty("erros", erros);
        return problema;
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Corpo sem Content-Length que passou do limite durante a leitura (ver LimiteDeCorpoFilter):
        // o erro chega embrulhado no de leitura do JSON, mas a resposta certa e 413, nao 400.
        for (Throwable causa = ex; causa != null; causa = causa.getCause()) {
            if (causa instanceof CorpoGrandeDemaisException grande) {
                HttpStatus grandeDemais = HttpStatus.valueOf(413);
                return handleExceptionInternal(ex, problema(grandeDemais, "Corpo grande demais", grande.getMessage()),
                        headers, grandeDemais, request);
            }
        }
        ProblemDetail problema = problema(HttpStatus.BAD_REQUEST, "Corpo da requisição ilegível",
                "O corpo da requisição não é um JSON válido ou tem um campo com tipo errado.");
        return handleExceptionInternal(ex, problema, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String nome = ex instanceof MethodArgumentTypeMismatchException m ? m.getName() : ex.getPropertyName();
        ProblemDetail problema = problema(HttpStatus.BAD_REQUEST, "Parâmetro inválido",
                "O parâmetro '" + nome + "' tem um valor com tipo errado.");
        return handleExceptionInternal(ex, problema, headers, status, request);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail ordenacaoInvalida(PropertyReferenceException ex) {
        return problema(HttpStatus.BAD_REQUEST, "Ordenação inválida",
                "Não é possível ordenar por '" + ex.getPropertyName() + "'.");
    }

    // ---- redes de seguranca -----------------------------------------------------

    /**
     * Se uma restricao do banco (unica, chave estrangeira, CHECK) for violada sem que a API tenha
     * antecipado a regra, o cliente recebe 409 e o log guarda a causa real. As regras importantes
     * devem ser checadas ANTES na camada de servico, com mensagem clara.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integridade(DataIntegrityViolationException ex) {
        LOG.warn("Violação de integridade não antecipada pela API: {}", ex.getMostSpecificCause().getMessage());
        return problema(HttpStatus.CONFLICT, "Violação de integridade",
                "A operação viola uma restrição de integridade dos dados.");
    }

    @ExceptionHandler(AcessoNegadoException.class)
    ProblemDetail acessoNegado(AcessoNegadoException ex) {
        return problema(HttpStatus.FORBIDDEN, ex.getTitulo(), ex.getMessage());
    }

    /**
     * As regras da autorizacao que o BANCO garante (14_autorizacao.sql) levantam ORA-20101 a
     * ORA-20110: por exemplo, a troca de cargo que deixaria a empresa sem administrador. Viram 422,
     * com a mensagem que o banco escreveu. Qualquer outro erro de banco continua sendo 500.
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ProblemDetail regraDoBanco(org.springframework.dao.DataAccessException ex) {
        for (Throwable causa = ex; causa != null; causa = causa.getCause()) {
            if (causa instanceof java.sql.SQLException sql && sql.getErrorCode() >= 20101 && sql.getErrorCode() <= 20110) {
                String mensagem = sql.getMessage() == null ? "" : sql.getMessage().split("\n")[0].replaceFirst("^ORA-\\d+:\\s*", "");
                return problema(HttpStatus.UNPROCESSABLE_ENTITY, "Regra de negócio violada", mensagem);
            }
        }
        return inesperado(ex);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail inesperado(Exception ex) {
        LOG.error("Erro inesperado", ex);
        return problema(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Ocorreu um erro inesperado. Tente novamente mais tarde.");
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setTitle(titulo);
        return problema;
    }
}
