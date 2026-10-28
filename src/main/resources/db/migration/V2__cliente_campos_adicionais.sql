-- V2: campos adicionais de CLIENTE.
--
-- Regra deste repositorio: o banco guarda TIPO, TAMANHO, FORMATO, UNICIDADE e CHAVES. Regra de
-- negocio (digito verificador do CNPJ, quem pode ser excluido, quem pode receber vinculo) fica na
-- API. Os defaults abaixo mantem compativel quem insere so CNPJ + RAZAO_SOCIAL (os testes de
-- contrato do repositorio de regras fazem exatamente isso).
ALTER TABLE CLIENTE ADD (
    NOME_FANTASIA   VARCHAR2(200),
    ATIVO           BOOLEAN DEFAULT TRUE NOT NULL,
    CRIADO_EM       TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL,
    ATUALIZADO_EM   TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL
);

ALTER TABLE CLIENTE ADD CONSTRAINT CK_CLIENTE_CNPJ CHECK (REGEXP_LIKE(CNPJ, '^[0-9]{14}$'));
