# tabelas-api-banco-usp

Tabelas de negócio e API do banco Oracle da Kawashi Sistemas, em **Java 24 + Spring Boot 4.1**.
Projeto da pós-graduação na USP.

O banco guarda **estrutura** (tipo, tamanho, formato, unicidade, chaves). Toda **regra de
negócio** mora na API. Este repositório roda sobre as regras de segurança do
`regras-banco-usp` (hardening, ambientes, perfis, auditoria, Vault): aquele define quem entra no
banco e como, este define o que existe dentro dos schemas e como se usa. Os dois juntos orquestram
o banco Oracle.

## O que já existe

- 6 tabelas padrão: `CLIENTE`, `ENDERECO`, `CONTATO`, `EMPRESA`, `FILIAL`, `SOCIO`.
- API REST para todas elas (criar, listar com paginação, buscar, atualizar, excluir), com
  validações de CNPJ/CPF/UF, regras de negócio e erros no formato RFC 9457.
- Migrações versionadas (Flyway). Detalhes em [`docs/api-e-regras.md`](docs/api-e-regras.md).
- Testes de integração contra o Oracle real, pelo mesmo caminho de produção (proxy).

## Rodando

Pré-requisitos: JDK 21 ou superior (testado com o 24), Maven 3.9 e o banco do `regras-banco-usp`
no ar (`docker compose up -d` dentro de `infra/`).

1. **Crie a conta da API** no repositório de regras. Ela entra no Oracle por proxy, no schema do
   ambiente, e já gera o `.env` local deste projeto (ignorado pelo git):

   ```powershell
   cd ..\regras-banco-usp\infra\scripts
   ./create-api-account.ps1 -Username API_TESTE_DEV -Ambiente TESTE_DEV -OutEnvFile ..\..\..\tabelas-api-banco-usp\.env
   ```

2. **Suba a API** (as migrações rodam sozinhas na primeira vez):

   ```bash
   mvn spring-boot:run
   ```

3. **Rode os testes** (todos, contra o Oracle real):

   ```bash
   mvn test
   ```

Configuração, por variável de ambiente ou pelo `.env`:

| Variável | Para quê | Padrão |
|---|---|---|
| `DB_URL` | URL JDBC | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |
| `DB_USER` | Usuário no formato `CONTA[KAWASHI_ENV_<AMBIENTE>]` (proxy) | sem padrão |
| `DB_PASSWORD` | Senha **da própria conta** (está no Vault em `kawashi-secrets/api/<conta>`) | sem padrão |
| `DB_POOL_MAX` | Tamanho máximo do pool de conexões | `10` |
| `PORT` | Porta HTTP | `8080` |

A saúde da aplicação fica em `/actuator/health`.

## O banco guarda estrutura, a API guarda regra

| O banco garante | A API garante |
|---|---|
| Tipo, tamanho máximo e `NOT NULL` de cada coluna | Mensagem clara em português para cada campo inválido (400) |
| Formato (`CHECK`): CNPJ com 14 dígitos, CPF com 11, UF com 2 letras, CEP com 8, telefone com 10 ou 11 | Dígito verificador de CNPJ e CPF, UF que existe de verdade, e-mail válido |
| Unicidade (`UNIQUE`) e chaves estrangeiras | CNPJ duplicado (409), pai inexistente (404), registro com dependentes (409) |
| Participação entre 0 e 100 | Soma das participações dos sócios até 100% |
| Nada além disso | Cliente inativo não recebe vínculo novo, só a matriz é empresa, filial herda a raiz do CNPJ, um único endereço/contato principal, imutabilidade de CNPJ e CPF |

Se uma restrição do banco for violada sem que a API a tenha antecipado, o cliente recebe um 409
genérico e o log guarda a causa real. As regras importantes são checadas antes, na camada de
serviço, com mensagem própria.

## Migrações (Flyway)

Ficam em `src/main/resources/db/migration`. A regra é a do Flyway: **uma migração aplicada nunca é
editada**; qualquer mudança de estrutura é uma migração nova. O Hibernate roda com
`ddl-auto=validate`: ele só confere se as entidades batem com as tabelas, e a aplicação não sobe se
não baterem.

O repositório de regras cria uma fixture mínima (`CLIENTE`) em `TESTE_DEV` e `DEV`, então esses
schemas **não estão vazios** na primeira execução. Por isso a `V1` só cria `CLIENTE` quando ela não
existe, e o Flyway usa `baseline-on-migrate` com versão `0`.

## Testes

Testes de integração sobem a aplicação inteira, conectam no Oracle real como conta de API (por
proxy) e chamam os endpoints por MockMvc. Cada teste roda numa transação que é desfeita no fim,
então não sobra dado no banco e os testes não interferem entre si. Os documentos usados são
CNPJ/CPF válidos e aleatórios (`GeradorDeDocumentos`), nunca reais.

Como a transação do teste mantém a sessão aberta, ela esconde um tipo de erro de produção
(`LazyInitializationException` fora da transação). Por isso a aplicação também foi exercitada por
HTTP real, com o roteiro completo: criar cliente, empresa, filial, sócios, endereço e contato,
violar as regras de propósito, e limpar tudo.

## Armadilhas encontradas pelo caminho

- **Spring Boot 4 mudou classes de pacote.** Por exemplo, `PropertyReferenceException` passou para
  `org.springframework.data.core` e `AutoConfigureMockMvc` para
  `org.springframework.boot.webmvc.test.autoconfigure`. Em caso de dúvida, listar as entradas do jar
  em vez de adivinhar.
- **Spring Data 4 trata todo getter como propriedade.** Um `getClienteId()` numa entidade que tem
  `cliente` quebra o `findByClienteId` (o Hibernate procura um atributo `clienteId` que não
  existe). O id do pai sai de `entidade.getCliente().getId()`, que não carrega o proxy.
- **`open-in-view` está desligado**, e os controladores montam a resposta depois que a transação do
  serviço fechou. Ler um relacionamento preguiçoso na resposta, além do id, quebraria em produção.
- **O Oracle mede `VARCHAR2(n)` em bytes por padrão**, e o banco é `AL32UTF8`: letra acentuada
  ocupa 2 bytes. Ver `docs/api-e-regras.md`.

## Pendências

- **Autenticação e autorização.** A API ainda não tem nenhuma. Está planejada para a entrega do
  schema `LOGIN`.
- Demais schemas do desenho original (`SEFAZ`, `ORGANIZACOES`, `LOGIN`, `ADMINISTRACAO`). A
  criação dos schemas é do repositório de regras; as tabelas, deste.
- **Regra "serviço lê todas as tabelas padrão".** As tabelas criadas pelo Flyway não recebem
  `GRANT SELECT` automático para os schemas de serviço (hoje só a `CLIENTE` recebe, pela fixture).
  A decisão de onde essa regra mora está em aberto, registrada no repositório de regras.
- **Conta de migração separada da conta de execução.** Hoje a mesma conta roda o Flyway e atende
  as requisições, e por proxy ela tem DDL no schema do ambiente.
- Empacotamento (Dockerfile) e integração contínua.

## Origem

O conteúdo inicial saiu do `regras-banco-usp` na separação entre regras e tabelas. A versão
completa de antes da separação está na tag `dominio-completo-pre-separacao` (commit `cadb773`)
daquele repositório. Os SQL originais e o desenho de domínio completo (incluindo `ORGANIZACOES`,
`LOGIN` e `ADMINISTRACAO`) continuam em [`oracle-init/`](oracle-init/) e
[`docs/arquitetura-dominio.md`](docs/arquitetura-dominio.md), **só como referência**: a fonte da
verdade da estrutura agora são as migrações do Flyway.
