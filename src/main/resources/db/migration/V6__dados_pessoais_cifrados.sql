-- V6: os dados pessoais passam a ser guardados CIFRADOS pela API (LGPD, art. 46: medidas tecnicas
-- para proteger dados pessoais). O banco deixa de ver o documento da pessoa e o valor do contato.
--
--   PESSOA.DOCUMENTO  -> DOCUMENTO_CIFRADO (o valor, cifrado com AES-256-GCM pela aplicacao)
--                        DOCUMENTO_INDICE  (impressao digital HMAC-SHA256, para a unicidade)
--   CONTATO.VALOR     -> VALOR_CIFRADO + VALOR_INDICE (idem)
--
-- A chave fica com a aplicacao (variaveis CIFRA_CHAVE_DADOS e CIFRA_CHAVE_INDICE), nunca no banco:
-- nem o dono das tabelas nem um DBA leem o valor. O porque de cada escolha esta em
-- docs/modelo-de-dados.md, secao "Criptografia e LGPD".
--
-- O que o banco deixa de conferir: que o documento tem 11 digitos para pessoa fisica e 14 para
-- juridica (ele nao ve mais os digitos). Essa regra passa a ser so da API. O que ele continua
-- garantindo: a unicidade (pelo indice) e que as colunas tem cara de cifra, nao de texto em claro.

-- Esta migracao troca colunas, nao converte dados: cifrar exige a chave, que o banco nao tem.
-- Se houver linhas, para aqui em vez de apagar dado em silencio.
DECLARE
    v_linhas NUMBER;
BEGIN
    FOR t IN (SELECT table_name FROM user_tables WHERE table_name IN ('PESSOA', 'CONTATO')) LOOP
        EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM ' || t.table_name INTO v_linhas;
        IF v_linhas > 0 THEN
            RAISE_APPLICATION_ERROR(-20001, 'V6 recusada: a tabela ' || t.table_name || ' tem ' || v_linhas
                || ' linha(s). Esta migracao nao cifra dados existentes (o banco nao tem a chave); exporte, apague e recadastre pela API.');
        END IF;
    END LOOP;
END;
/

ALTER TABLE PESSOA DROP CONSTRAINT UK_PESSOA;
ALTER TABLE PESSOA DROP CONSTRAINT CK_PESSOA_DOCUMENTO;
ALTER TABLE PESSOA DROP COLUMN DOCUMENTO;

ALTER TABLE PESSOA ADD (
    DOCUMENTO_CIFRADO VARCHAR2(100) NOT NULL,
    DOCUMENTO_INDICE  VARCHAR2(64)  NOT NULL
);

-- A mesma regra de antes (um documento por empresa e por tipo), agora sobre a impressao digital.
ALTER TABLE PESSOA ADD CONSTRAINT UK_PESSOA UNIQUE (EMPRESA_ID, DOCUMENTO_INDICE, TIPO);
-- Texto em claro gravado por engano (um INSERT manual, um erro de codigo) e recusado pelo banco.
ALTER TABLE PESSOA ADD CONSTRAINT CK_PESSOA_DOC_CIFRADO CHECK (DOCUMENTO_CIFRADO LIKE 'v1:%' AND LENGTH(DOCUMENTO_CIFRADO) >= 43);
ALTER TABLE PESSOA ADD CONSTRAINT CK_PESSOA_DOC_INDICE CHECK (REGEXP_LIKE(DOCUMENTO_INDICE, '^[0-9a-f]{64}$'));

COMMENT ON COLUMN PESSOA.DOCUMENTO_CIFRADO IS 'CPF ou CNPJ cifrado pela API (AES-256-GCM). O banco nao tem a chave.';
COMMENT ON COLUMN PESSOA.DOCUMENTO_INDICE IS 'Impressao digital (HMAC-SHA256 com chave) do documento, para unicidade e busca exata.';

ALTER TABLE CONTATO DROP COLUMN VALOR;

ALTER TABLE CONTATO ADD (
    VALOR_CIFRADO VARCHAR2(1000) NOT NULL,
    VALOR_INDICE  VARCHAR2(64)   NOT NULL
);

ALTER TABLE CONTATO ADD CONSTRAINT CK_CONTATO_VALOR_CIFRADO CHECK (VALOR_CIFRADO LIKE 'v1:%' AND LENGTH(VALOR_CIFRADO) >= 43);
ALTER TABLE CONTATO ADD CONSTRAINT CK_CONTATO_VALOR_INDICE CHECK (REGEXP_LIKE(VALOR_INDICE, '^[0-9a-f]{64}$'));

-- "O mesmo contato nao se repete no mesmo dono" passa a ser garantido tambem pelo banco: antes era
-- so uma checagem da API. Um dos dois (FILIAL_ID, PESSOA_ID) e sempre nulo; o indice unico cobre os dois casos.
CREATE UNIQUE INDEX UK_CONTATO_DONO_VALOR ON CONTATO (FILIAL_ID, PESSOA_ID, TIPO, VALOR_INDICE);

COMMENT ON COLUMN CONTATO.VALOR_CIFRADO IS 'E-mail ou telefone cifrado pela API (AES-256-GCM). O banco nao tem a chave.';
COMMENT ON COLUMN CONTATO.VALOR_INDICE IS 'Impressao digital (HMAC-SHA256 com chave) do valor, para nao repetir o contato no mesmo dono.';
