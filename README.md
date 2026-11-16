# tabelas-api-banco-usp

Tabelas de negócio e API do banco Oracle da Kawashi Sistemas, em **Java 24 + Spring Boot 4.1**.
Projeto da pós-graduação na USP.

O banco guarda **estrutura** (tipo, tamanho, formato, unicidade, chaves). Toda **regra de
negócio** mora na API. Este repositório roda sobre as regras de segurança do
`regras-banco-usp` (hardening, ambientes, perfis, auditoria, Vault): aquele define quem entra no
banco e como, este define o que existe dentro dos schemas e como se usa. Os dois juntos orquestram
o banco Oracle.

## O que já existe

- **Cadastros em torno de `PESSOA`:** `EMPRESA`, `FILIAL`, `PESSOA` (cliente, colaborador, sócio ou
  fornecedor, conforme o campo `TIPO`) e as tabelas auxiliares `CONTATO` e `ENDERECO`, que servem a
  uma filial ou a uma pessoa.
- **API REST** para todos eles (criar, listar com paginação, buscar, atualizar, excluir), com
  validações de CNPJ/CPF/UF, regras de negócio e erros no formato RFC 9457.
- **Logs da aplicação** num schema separado (`KAWASHI_ENV_<AMBIENTE>_LOG`), em que a API só insere:
  quem mudou qual registro, encadeado por hash, e cada chamada à API.
- Migrações versionadas (Flyway) e testes de integração contra o Oracle real, pelo mesmo caminho de
  produção (proxy).

Documentação:

- [`docs/modelo-de-dados.md`](docs/modelo-de-dados.md): as tabelas, os schemas e o porquê de cada
  decisão, incluindo o que ainda é proposta (login e autorização).
- [`docs/diagrama-tabelas.html`](docs/diagrama-tabelas.html): o diagrama, campo a campo (abrir no
  navegador).
- [`docs/api-e-regras.md`](docs/api-e-regras.md): endpoints, regras, logs, concorrência.

## O que vem a seguir

Desenhado e documentado, ainda não implementado:

1. **Login** (schemas `LOGIN` e `SESSAO`): senha com troca a cada 90 dias, token que expira,
   bloqueio por tentativas, 2FA opcional, dispositivo de confiança, localização suspeita.
2. **Autorização**: perfis, permissões e o vínculo do usuário com cada empresa (multiempresa).

Enquanto isso, **a API não tem autenticação**: quem alcança a porta lê e grava cadastros. O que já
limita o estrago: a conta de banco que atende os pedidos não consegue apagar nem alterar tabela, não
consegue mexer no log, e a API pode exigir HTTPS.

## Rodando

Pré-requisitos: JDK 21 ou superior (testado com o 24), Maven 3.9 e o banco do `regras-banco-usp`
no ar (`docker compose up -d` dentro de `infra/`).

1. **Crie as duas contas da API** no repositório de regras. As duas entram no Oracle por proxy e
   vão para o mesmo `.env` local deste projeto (ignorado pelo git):

   ```powershell
   cd ..\regras-banco-usp\infra\scripts
   ./create-api-account.ps1 -Username API_TESTE_DEV_EXEC -Ambiente TESTE_DEV -OutEnvFile ..\..\..\tabelas-api-banco-usp\.env
   ./create-api-account.ps1 -Username API_TESTE_DEV      -Ambiente TESTE_DEV -Papel Migracao -OutEnvFile ..\..\..\tabelas-api-banco-usp\.env
   ```

   A primeira é a de **execução**: atende os pedidos e só lê e grava linhas. A segunda é a de
   **migração**: entra como dona das tabelas e só é usada na subida, pelo Flyway. Ver "Contas de
   banco" em [`docs/api-e-regras.md`](docs/api-e-regras.md).

2. **Suba a API** (as migrações rodam sozinhas na primeira vez):

   ```bash
   mvn spring-boot:run
   ```

3. **Rode os testes** (todos, contra o Oracle real):

   ```bash
   mvn test
   ```

4. **HTTPS** (opcional no desenvolvimento, obrigatório fora dele). Gera um certificado autoassinado
   para `localhost` e liga o HTTPS obrigatório no `.env`:

   ```powershell
   .\scripts\gerar-certificado-dev.ps1
   ```

   Depois disso a API sobe em `https://localhost:8080` e recusa HTTP puro. Os testes não são
   afetados: eles fixam a própria configuração de HTTPS.

Configuração, por variável de ambiente ou pelo `.env`:

| Variável | Para quê | Padrão |
|---|---|---|
| `DB_URL` | URL JDBC | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |
| `DB_SCHEMA` | Schema do ambiente, onde ficam as tabelas (`KAWASHI_ENV_<AMBIENTE>`) | sem padrão |
| `DB_USER` | Conta de **execução**, no formato `CONTA[KAWASHI_ENV_<AMBIENTE>_APP]` | sem padrão |
| `DB_PASSWORD` | Senha da conta de execução | sem padrão |
| `DB_MIGRACAO_USER` | Conta de **migração**, no formato `CONTA[KAWASHI_ENV_<AMBIENTE>]` | sem padrão |
| `DB_MIGRACAO_PASSWORD` | Senha da conta de migração | sem padrão |
| `DB_POOL_MAX` | Tamanho máximo do pool de conexões | `10` |
| `PORT` | Porta | `8080` |
| `EXIGIR_HTTPS` | `true` recusa pedido a `/api/**` que não chegou por HTTPS | `false` |
| `SSL_ATIVO`, `SSL_KEYSTORE`, `SSL_KEYSTORE_PASSWORD` | HTTPS direto na aplicação (keystore PKCS12) | desligado |

As senhas também ficam no Vault, em `kawashi-secrets/api/<conta>`. O schema de log é deduzido
sozinho (`DB_SCHEMA` seguido de `_LOG`). A saúde da aplicação fica em `/actuator/health`.

## O banco guarda estrutura, a API guarda regra

| O banco garante | A API garante |
|---|---|
| Tipo, tamanho máximo e `NOT NULL` de cada coluna | Mensagem clara em português para cada campo inválido (400) |
| Formato (`CHECK`): CNPJ com 14 dígitos, CPF com 11, UF com 2 letras, CEP com 8 | Dígito verificador de CNPJ e CPF, UF que existe de verdade, e-mail e telefone válidos |
| Os valores possíveis de `TIPO` e `NATUREZA`; participação só em sócio, cargo só em colaborador | Participação obrigatória para sócio; soma das participações até 100% |
| Unicidade (`UNIQUE`) e chaves estrangeiras; contato e endereço com exatamente um dono | Duplicidade (409), pai inexistente (404), registro com dependentes (409) |
| Nada além disso | Pai inativo não recebe filho novo, só a matriz é empresa, filial herda a raiz do CNPJ, um único principal, imutabilidade de documento e tipo |

Se uma restrição do banco for violada sem que a API a tenha antecipado, o cliente recebe um 409
genérico e o log guarda a causa real. As regras importantes são checadas antes, na camada de
serviço, com mensagem própria.

## Migrações (Flyway)

Ficam em `src/main/resources/db/migration`. A regra é a do Flyway: **uma migração aplicada nunca é
editada**; qualquer mudança de estrutura é uma migração nova. O Hibernate roda com
`ddl-auto=validate`: ele só confere se as entidades batem com as tabelas, e a aplicação não sobe se
não baterem.

- `V1` a `V4`: o primeiro modelo (uma tabela por papel: `CLIENTE`, `SOCIO`...).
- `V5`: troca pelo modelo em torno de `PESSOA`. Ela **não converte dados**: recusa rodar se alguma
  tabela antiga tiver linhas, em vez de apagar em silêncio. O modelo antigo só existiu em
  desenvolvimento.

As tabelas de **log não são criadas aqui**, e sim pelo repositório de regras
(`infra/oracle-init/11_schema_log.sql`). É de propósito: quem cria uma tabela é dono dela e consegue
apagar as linhas. Com o dono sendo um schema em que ninguém loga, "a API só insere no log" deixa de
ser promessa e vira permissão do banco.

## Testes

Testes de integração sobem a aplicação inteira, conectam no Oracle real como conta de API (por
proxy) e chamam os endpoints por MockMvc. Cada teste roda numa transação que é desfeita no fim,
então não sobra dado no banco e os testes não interferem entre si. Os documentos usados são
CNPJ/CPF válidos e aleatórios (`GeradorDeDocumentos`), nunca reais.

A suíte tem **95 testes** (última execução de `mvn test`, todos passando). É menor que a do modelo
antigo (296): são menos tabelas, e os testes foram reescritos mais densos, vários casos por teste.

| Grupo | Testes |
|---|---|
| `EmpresaEFilialApiTest` | 10 |
| `PessoaApiTest` (tipos, natureza e documento, participação, soma dos sócios) | 15 |
| `ContatoEEnderecoApiTest` (as mesmas regras com dono filial e dono pessoa) | 12 |
| `LogDaAplicacaoTest` (rastro de cada mudança, encadeamento, o que a API não consegue fazer no log) | 7 |
| `ServidorRealTest` (porta real: 12 pedidos simultâneos contra as travas e contra o log, corpo fragmentado) | 8 |
| `DocumentosTest` (CNPJ, CPF, UF, sem banco) | 31 |
| `ConexaoProxyTest` (as duas contas de banco; a de execução não cria, altera nem apaga tabela) | 6 |
| `HttpsObrigatorioTest` (HTTP puro recusado, cabeçalhos de segurança) | 4 |
| `TratadorDeErrosTest` | 2 |

O `ServidorRealTest` é a exceção à transação desfeita: precisa gravar de verdade para disputar as
travas. Ele apaga os cadastros que criou; as linhas de log ficam, porque a API não tem como
apagá-las.

Como a transação do teste mantém a sessão aberta, ela esconde um tipo de erro de produção. Por isso
existe também um roteiro por **HTTP real**, [`scripts/smoke-http.ps1`](scripts/smoke-http.ps1): com
a API no ar, cria empresa, filial, pessoas de cada tipo, contatos e endereços, viola as regras de
propósito (39 verificações) e apaga os cadastros que criou, conferindo que nada sobrou.

```bash
PORT=18080 mvn spring-boot:run          # em um terminal
```

```powershell
.\scripts\smoke-http.ps1 -Base http://localhost:18080
```

## Armadilhas encontradas pelo caminho

- **Spring Boot 4 mudou classes de pacote.** Por exemplo, `PropertyReferenceException` passou para
  `org.springframework.data.core` e `AutoConfigureMockMvc` para
  `org.springframework.boot.webmvc.test.autoconfigure`. Em caso de dúvida, listar as entradas do jar
  em vez de adivinhar.
- **`open-in-view` está desligado**, e os controladores montam a resposta depois que a transação do
  serviço fechou. Por isso as entidades guardam o pai só pela chave (`empresaId`, `filialId`,
  `pessoaId`), sem associação JPA: nada na resposta depende de carga preguiçosa.
- **O Oracle mede `VARCHAR2(n)` em bytes por padrão**, e o banco é `AL32UTF8`: letra acentuada
  ocupa 2 bytes. As colunas de texto livre são `VARCHAR2(n CHAR)`.
- **Falta de privilégio tem dois códigos.** Pelo `sqlplus` aparece `ORA-01031`; pelo JDBC do Oracle
  23, o mais específico `ORA-41900`. Os testes aceitam os dois.

## Pendências

- **Login e autorização** (ver "O que vem a seguir").
- **O log de alteração não guarda o valor antigo nem o novo**, só que o registro mudou.
- Empacotamento (Dockerfile) e integração contínua.

## Origem

O conteúdo inicial saiu do `regras-banco-usp` na separação entre regras e tabelas. A versão
completa de antes da separação está na tag `dominio-completo-pre-separacao` (commit `cadb773`)
daquele repositório. O primeiro modelo de tabelas e sua API estão no histórico do git deste
repositório, até o commit `49913a6`.
