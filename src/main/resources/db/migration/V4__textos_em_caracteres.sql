-- V4: campos de texto livre passam a medir o limite em CARACTERES, nao em bytes.
--
-- No Oracle, VARCHAR2(200) sem a palavra CHAR mede o limite em BYTES (NLS_LENGTH_SEMANTICS=BYTE) e
-- este banco e AL32UTF8, onde uma letra acentuada ocupa 2 bytes. A API valida o tamanho em
-- caracteres (@Size), entao uma razao social de 200 letras "a" acentuadas passava na validacao e
-- estourava a coluna (ORA-12899: real 400, maximo 200). Reproduzido por LimitesDeTextoTest.
--
-- Ficam de fora as colunas que so guardam ASCII e tem formato garantido por CHECK: CNPJ, CPF, CEP,
-- UF e TELEFONE (so digitos ou letras sem acento), onde bytes e caracteres sao a mesma coisa.
-- VARCHAR2(n CHAR) comporta ate 4 vezes n bytes, bem abaixo do teto de 4000 bytes da coluna.
ALTER TABLE CLIENTE MODIFY (
    RAZAO_SOCIAL    VARCHAR2(200 CHAR),
    NOME_FANTASIA   VARCHAR2(200 CHAR)
);

ALTER TABLE ENDERECO MODIFY (
    LOGRADOURO      VARCHAR2(200 CHAR),
    NUMERO          VARCHAR2(20 CHAR),
    COMPLEMENTO     VARCHAR2(100 CHAR),
    BAIRRO          VARCHAR2(100 CHAR),
    CIDADE          VARCHAR2(100 CHAR)
);

ALTER TABLE CONTATO MODIFY (
    NOME            VARCHAR2(150 CHAR),
    EMAIL           VARCHAR2(150 CHAR)
);

ALTER TABLE EMPRESA MODIFY (
    RAZAO_SOCIAL    VARCHAR2(200 CHAR),
    NOME_FANTASIA   VARCHAR2(200 CHAR)
);

ALTER TABLE FILIAL MODIFY (
    NOME            VARCHAR2(150 CHAR)
);

ALTER TABLE SOCIO MODIFY (
    NOME            VARCHAR2(150 CHAR)
);
