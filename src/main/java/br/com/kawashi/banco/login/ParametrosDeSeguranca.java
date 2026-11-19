package br.com.kawashi.banco.login;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Os numeros das regras do login, lidos da tabela PARAMETRO_SEGURANCA a cada uso (sao poucas
 * linhas). A API so tem SELECT nela: quem invadir a API nao consegue afrouxar as regras, e toda
 * mudanca feita no banco vai sozinha para o LOG_SEGURANCA (trigger do repositorio de regras).
 *
 * <p>Falta de parametro e erro, nunca um valor padrao: na duvida, a API recusa em vez de aplicar
 * uma regra mais fraca sem ninguem saber.
 */
@Component
public class ParametrosDeSeguranca {

    public record Parametros(int tokenMinutos, int tokenTetoHoras, int sessoesMaximas, int loginMaxTentativas,
            int loginBloqueioMinutos, int ipMaxFalhas, int ipJanelaMinutos, int ipBloqueioMinutos,
            int senhaValidadeDias, int senhaHistorico, int senhaMinimo) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;

    public ParametrosDeSeguranca(JdbcTemplate jdbc, SchemasDoLogin schemas) {
        this.jdbc = jdbc;
        this.schemas = schemas;
    }

    public Parametros atuais() {
        Map<String, Integer> valores = new HashMap<>();
        jdbc.query("SELECT CHAVE, VALOR FROM " + schemas.login("PARAMETRO_SEGURANCA"),
                linha -> { valores.put(linha.getString(1), linha.getInt(2)); });
        return new Parametros(
                exigir(valores, "TOKEN_MINUTOS"), exigir(valores, "TOKEN_TETO_HORAS"), exigir(valores, "SESSOES_MAXIMAS"),
                exigir(valores, "LOGIN_MAX_TENTATIVAS"), exigir(valores, "LOGIN_BLOQUEIO_MINUTOS"),
                exigir(valores, "IP_MAX_FALHAS"), exigir(valores, "IP_JANELA_MINUTOS"), exigir(valores, "IP_BLOQUEIO_MINUTOS"),
                exigir(valores, "SENHA_VALIDADE_DIAS"), exigir(valores, "SENHA_HISTORICO"), exigir(valores, "SENHA_MINIMO"));
    }

    private static int exigir(Map<String, Integer> valores, String chave) {
        Integer valor = valores.get(chave);
        if (valor == null || valor <= 0) {
            throw new IllegalStateException("Parâmetro de segurança ausente ou inválido: " + chave);
        }
        return valor;
    }
}
