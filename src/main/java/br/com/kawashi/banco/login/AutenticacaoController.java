package br.com.kawashi.banco.login;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * O login por passkey. Quem chama e o BFF (servidor do site); as respostas de passkey sao o
 * {@code PublicKeyCredential.toJSON()} do navegador, repassado como veio. Ver as regras em
 * {@link ServicoDeLogin}, {@link Passkeys} e {@link Tokens}.
 */
@RestController
@RequestMapping("/api/auth")
public class AutenticacaoController {

    public record PedidoDeLogin(@NotNull(message = "Informe a credencial") JsonNode credencial) {
    }

    public record PedidoDeConvite(
            @NotBlank(message = "Informe o convite") @Size(max = 100) String convite) {
    }

    public record AceiteDeConvite(
            @NotBlank(message = "Informe o convite") @Size(max = 100) String convite,
            @NotBlank(message = "Dê um nome à passkey (ex.: Notebook)") @Size(max = 60, message = "O nome aceita no máximo 60 caracteres")
            @Pattern(regexp = "[\\p{L}0-9 .,\\-()']+", message = "Use só letras, números e pontuação simples") String nome,
            @NotNull(message = "Informe a credencial") JsonNode credencial) {
    }

    public record NovaPasskey(
            @NotBlank(message = "Dê um nome à passkey (ex.: Celular)") @Size(max = 60, message = "O nome aceita no máximo 60 caracteres")
            @Pattern(regexp = "[\\p{L}0-9 .,\\-()']+", message = "Use só letras, números e pontuação simples") String nome,
            @NotNull(message = "Informe a credencial") JsonNode credencial) {
    }

    public record PedidoDeRecuperacao(
            @NotBlank(message = "Informe o e-mail") @Size(max = 150) String email,
            @NotBlank(message = "Informe o código") @Size(max = 40) String codigo) {

        @Override
        public String toString() {
            return "PedidoDeRecuperacao[***]";
        }
    }

    /** As horas saem com fuso explicito (UTC, "Z"): o banco grava tudo em UTC. */
    public record RespostaDoLogin(String token, String tipo, String escopo, OffsetDateTime expiraEm, OffsetDateTime expiraAbsolutoEm,
            List<String> codigosDeRecuperacao) {
    }

    public record Eu(long usuarioId, long pessoaId, String escopo, OffsetDateTime expiraEm, OffsetDateTime expiraAbsolutoEm,
            Long empresaId, java.util.Set<String> permissoes, boolean adminPlataforma, boolean termosPendentes) {
    }

    public record EscolhaDeEmpresa(@NotNull(message = "Informe a empresa") Long empresaId) {
    }

    public record AceiteDeTermos(@NotNull(message = "Informe os termos aceitos") @Size(min = 1, max = 10, message = "Informe os termos aceitos")
            List<@NotNull Long> termos) {
    }

    private final ServicoDeLogin servico;
    private final br.com.kawashi.banco.autorizacao.Autorizacao autorizacao;
    private final Termos termos;

    public AutenticacaoController(ServicoDeLogin servico, br.com.kawashi.banco.autorizacao.Autorizacao autorizacao, Termos termos) {
        this.servico = servico;
        this.autorizacao = autorizacao;
        this.termos = termos;
    }

    // ---- sem token ---------------------------------------------------------------------------

    @PostMapping("/passkey/login/opcoes")
    public ResponseEntity<?> opcoesDeLogin(HttpServletRequest requisicao) {
        return responder(servico.opcoesDeLogin(FiltroDoBff.ipDoCliente(requisicao)), "Não foi possível entrar.");
    }

    @PostMapping("/passkey/login")
    public ResponseEntity<?> entrar(@Valid @RequestBody PedidoDeLogin pedido, HttpServletRequest requisicao) {
        return responder(servico.entrar(pedido.credencial().toString(), FiltroDoBff.ipDoCliente(requisicao)), "Não foi possível entrar.");
    }

    @PostMapping("/convite/opcoes")
    public ResponseEntity<?> opcoesDoConvite(@Valid @RequestBody PedidoDeConvite pedido, HttpServletRequest requisicao) {
        return responder(servico.opcoesDoConvite(pedido.convite(), FiltroDoBff.ipDoCliente(requisicao)), "Convite inválido, já usado ou vencido.");
    }

    @PostMapping("/convite")
    public ResponseEntity<?> aceitarConvite(@Valid @RequestBody AceiteDeConvite pedido, HttpServletRequest requisicao) {
        return responder(servico.aceitarConvite(pedido.convite(), pedido.credencial().toString(), pedido.nome().strip(),
                FiltroDoBff.ipDoCliente(requisicao)), "Não foi possível cadastrar a passkey com este convite.");
    }

    @PostMapping("/recuperacao")
    public ResponseEntity<?> recuperar(@Valid @RequestBody PedidoDeRecuperacao pedido, HttpServletRequest requisicao) {
        return responder(servico.recuperar(pedido.email(), pedido.codigo(), FiltroDoBff.ipDoCliente(requisicao)),
                "E-mail ou código de recuperação inválido.");
    }

    // ---- com token ---------------------------------------------------------------------------

    @GetMapping("/passkeys")
    public List<Map<String, Object>> passkeys(HttpServletRequest requisicao) {
        return servico.passkeysDe(sessao(requisicao));
    }

    @PostMapping("/passkeys/opcoes")
    public Map<String, Object> opcoesDeNovaPasskey(HttpServletRequest requisicao) {
        return Map.of("publicKey", servico.opcoesDeNovaPasskey(sessao(requisicao)));
    }

    @PostMapping("/passkeys")
    public ResponseEntity<?> novaPasskey(@Valid @RequestBody NovaPasskey pedido, HttpServletRequest requisicao) {
        return servico.novaPasskey(sessao(requisicao), pedido.credencial().toString(), pedido.nome().strip(), FiltroDoBff.ipDoCliente(requisicao))
                .<ResponseEntity<?>>map(id -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "nome", pedido.nome().strip())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problema(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Passkey não cadastrada", "A resposta do aparelho não pôde ser verificada. Tente de novo.")));
    }

    @DeleteMapping("/passkeys/{id}")
    public ResponseEntity<Void> removerPasskey(@PathVariable long id, HttpServletRequest requisicao) {
        servico.removerPasskey(sessao(requisicao), id, FiltroDoBff.ipDoCliente(requisicao));
        return ResponseEntity.noContent().build();
    }

    /** Gera codigos novos (os anteriores deixam de valer). Mostrados uma unica vez. */
    @PostMapping("/codigos-de-recuperacao")
    public Map<String, Object> novosCodigos(HttpServletRequest requisicao) {
        return Map.of("codigos", servico.novosCodigos(sessao(requisicao), FiltroDoBff.ipDoCliente(requisicao)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> sair(HttpServletRequest requisicao) {
        servico.sair(sessao(requisicao), FiltroDoBff.ipDoCliente(requisicao));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sair-de-todos")
    public ResponseEntity<Void> sairDeTodos(HttpServletRequest requisicao) {
        servico.sairDeTodos(sessao(requisicao), FiltroDoBff.ipDoCliente(requisicao));
        return ResponseEntity.noContent().build();
    }

    /** Quem esta logado, em qual empresa, com quais permissoes nela, e se e administrador da plataforma. */
    @GetMapping("/eu")
    public Eu eu(HttpServletRequest requisicao) {
        Tokens.Sessao s = sessao(requisicao);
        return new Eu(s.usuarioId(), s.pessoaId(), s.escopo(), utc(s.expiraEm()), utc(s.expiraAbsolutoEm()), s.empresaId(),
                autorizacao.permissoesDoPedido(), autorizacao.ehAdminPlataforma(s.usuarioId()), termos.temPendencia(s.usuarioId()));
    }

    /** As empresas em que o usuario entra, com o cargo em cada uma. */
    @GetMapping("/empresas")
    public java.util.List<br.com.kawashi.banco.autorizacao.Autorizacao.Vinculo> empresas(HttpServletRequest requisicao) {
        return autorizacao.vinculosAtivos(sessao(requisicao).usuarioId());
    }

    /** Prende a sessao a uma empresa: devolve um token NOVO (o atual deixa de valer). */
    @PostMapping("/empresa")
    public ResponseEntity<?> escolherEmpresa(@Valid @RequestBody EscolhaDeEmpresa pedido, HttpServletRequest requisicao) {
        return responder(servico.escolherEmpresa(sessao(requisicao), pedido.empresaId(), FiltroDoBff.ipDoCliente(requisicao)), "");
    }

    /** Os termos de uso e a politica de privacidade vigentes, com o texto e se o usuario ja aceitou cada um. */
    @GetMapping("/termos")
    public List<Termos.Termo> termosVigentes(HttpServletRequest requisicao) {
        return termos.vigentes(sessao(requisicao).usuarioId());
    }

    /** Aceita versoes VIGENTES dos termos (ids de GET /termos). Id antigo ou desconhecido: 422, nada gravado. */
    @PostMapping("/termos/aceite")
    public ResponseEntity<?> aceitarTermos(@Valid @RequestBody AceiteDeTermos pedido, HttpServletRequest requisicao) {
        Tokens.Sessao s = sessao(requisicao);
        if (!termos.aceitar(s.usuarioId(), pedido.termos(), FiltroDoBff.ipDoCliente(requisicao))) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problema(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Termo inválido", "Aceite as versões vigentes dos termos (elas podem ter mudado: recarregue)."));
        }
        return ResponseEntity.ok(Map.of("termosPendentes", termos.temPendencia(s.usuarioId())));
    }

    // ---- apoio ---------------------------------------------------------------------------------

    private static ResponseEntity<?> responder(ServicoDeLogin.Resultado resultado, String mensagemDeRecusa) {
        return switch (resultado) {
            case ServicoDeLogin.Opcoes o -> ResponseEntity.ok(Map.of("publicKey", o.publicKey()));
            case ServicoDeLogin.Entrou e -> ResponseEntity.ok(new RespostaDoLogin(e.token().token(), "Bearer", e.token().escopo(),
                    utc(e.token().expiraEm()), utc(e.token().expiraAbsolutoEm()), e.codigosDeRecuperacao()));
            case ServicoDeLogin.Recusado r -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer realm=\"kawashi\"")
                    .body(problema(HttpStatus.UNAUTHORIZED, "Não autenticado", mensagemDeRecusa));
            case ServicoDeLogin.IpBloqueado b -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(b.segundos()))
                    .body(problema(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas",
                            "Muitas tentativas com falha vindas deste endereço. Tente de novo mais tarde."));
        };
    }

    /** O banco grava as horas do login em UTC (13_schema_login.sql); aqui ganham o fuso explicito. */
    private static OffsetDateTime utc(LocalDateTime emUtc) {
        return emUtc.atOffset(ZoneOffset.UTC);
    }

    private static Tokens.Sessao sessao(HttpServletRequest requisicao) {
        // O filtro garante a sessao em todo caminho daqui que nao e uma cerimonia sem token.
        return SessaoAtual.de(requisicao).orElseThrow(() -> new IllegalStateException("Pedido sem sessão depois do filtro."));
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalhe);
        p.setTitle(titulo);
        return p;
    }
}
