-- V1: tabela CLIENTE (padrao).
--
-- O repositorio de regras (regras-banco-usp) cria uma fixture minima com esta MESMA tabela em
-- KAWASHI_ENV_TESTE_DEV e KAWASHI_ENV_DEV, no primeiro boot do banco, para os testes de contrato
-- dele terem o que exercitar. Por isso esta migracao so cria a tabela quando ela ainda nao existe:
-- onde a fixture ja criou (as 3 colunas abaixo, identicas), a V1 e um no-op e a V2 em diante
-- evolui a estrutura. Nos outros ambientes (INFRA, HOMOLOG...) a tabela e criada aqui.
DECLARE
    v_existe NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_existe FROM user_tables WHERE table_name = 'CLIENTE';
    IF v_existe = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE CLIENTE (
            ID              NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            CNPJ            VARCHAR2(14) NOT NULL UNIQUE,
            RAZAO_SOCIAL    VARCHAR2(200) NOT NULL
        )';
    END IF;
END;
/
