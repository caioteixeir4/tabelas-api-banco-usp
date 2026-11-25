-- Roda depois de TODA execucao do Flyway (callback "afterMigrate"), como a conta de MIGRACAO, que
-- entra como o dono das tabelas.
--
-- A API atende os pedidos com outra conta, a de EXECUCAO, que entra no schema
-- KAWASHI_ENV_<AMBIENTE>_APP: um schema sem objetos e sem privilegio de criar nada. Aqui o dono
-- concede a ele so o que a API precisa no dia a dia: ler e gravar linhas. Nada de ALTER, DROP,
-- INDEX ou REFERENCES. Assim, quem invadir a API nao consegue apagar nem alterar tabela.
--
-- Concede em todas as tabelas do schema a cada execucao (e idempotente), para uma tabela nova de
-- uma migracao futura nao ficar esquecida. O historico do Flyway fica de fora.
DECLARE
    v_app    VARCHAR2(128) := SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') || '_APP';
    v_existe NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_existe FROM all_users WHERE username = v_app;
    IF v_existe = 0 THEN
        RAISE_APPLICATION_ERROR(-20002, 'O schema de execucao ' || v_app || ' nao existe. Ele e criado pelo '
            || 'repositorio de regras (infra/oracle-init/09_environments.sql, secao 2.1).');
    END IF;
    FOR t IN (SELECT table_name FROM user_tables WHERE table_name NOT LIKE 'flyway%') LOOP
        EXECUTE IMMEDIATE 'GRANT SELECT, INSERT, UPDATE, DELETE ON "' || t.table_name || '" TO ' || v_app;
    END LOOP;

    -- O login (USUARIO, no schema _LOGIN, criado pelo repositorio de regras) aponta para a PESSOA
    -- deste schema. A chave entre schemas so pode ser criada depois que a PESSOA existe, e a
    -- PESSOA e criada aqui, pelo Flyway: por isso a ligacao e pedida a cada migracao. A procedure
    -- (13_schema_login.sql) so liga o proprio ambiente de quem chama, e nao faz nada se ja estiver
    -- ligada.
    SYS.KAWASHI_LIGAR_LOGIN_PESSOA;

    -- A autorizacao (14_autorizacao.sql) liga PERFIL, USUARIO_EMPRESA e TOKEN a EMPRESA deste schema e
    -- cria o painel V_USUARIO_ACESSOS, que le a PESSOA. Mesmo motivo: so depois que as tabelas existem.
    SYS.KAWASHI_LIGAR_AUTORIZACAO;

    -- VPD (19_vpd_infra.sql): a equipe de Infra nao le nem altera linha das tabelas de negocio.
    -- A politica so pode ser posta depois que as tabelas existem; a procedure nao repete o que ja tem.
    SYS.KAWASHI_LIGAR_VPD;
END;
/
