# API e regras de negócio

Referência das 6 fatias da API: endpoints, regras e as decisões de interpretação tomadas. A
estrutura das tabelas está nas migrações (`src/main/resources/db/migration`); aqui está o que a API
faz por cima delas.

## Convenções

- **Caminho base:** `/api`. JSON em UTF-8. Datas em ISO-8601, em UTC e sem fuso (`criadoEm`,
  `atualizadoEm`).
- **Status:** `201` com header `Location` ao criar, `204` ao excluir, `400` para campo inválido,
  `404` para registro que não existe, `409` para duplicidade ou registro com dependentes, `422`
  para regra de negócio violada.
- **Erros** no formato RFC 9457 (`application/problem+json`). Validação de campos devolve a lista
  em `erros`:

  ```json
  {
    "title": "Dados inválidos",
    "status": 400,
    "detail": "Um ou mais campos não passaram na validação.",
    "erros": [{ "campo": "cnpj", "mensagem": "CNPJ inválido" }]
  }
  ```

- **Paginação:** `?page=0&size=20&sort=campo,asc` (páginas começam em 0, `size` vai até 100).
  Resposta: `{ "itens": [...], "pagina": 0, "tamanho": 20, "total": 3, "totalPaginas": 1 }`.
  Ordenar por campo que não existe devolve `400`, e `page` grande demais (o deslocamento não cabe
  num inteiro) também, em vez de estourar em `500`. Toda listagem tem desempate por `id`, então a
  ordem é estável mesmo quando o campo ordenado se repete (várias razões sociais iguais) e nenhuma
  linha some ou aparece duas vezes entre páginas.
- **`PUT` é substituição completa**: o corpo traz todos os campos editáveis. Campos opcionais
  omitidos viram nulos, e `principal` omitido vale `false`.
- **Normalização na entrada:** CNPJ, CPF, CEP e telefone aceitam pontuação e são gravados só com
  dígitos; e-mail em minúscula; UF em maiúscula; textos aparados, e texto opcional em branco vira
  nulo.

## Endpoints

| Recurso | Endpoints |
|---|---|
| Cliente | `POST /api/clientes`, `GET /api/clientes` (filtros `cnpj`, `razaoSocial`, `ativo`), `GET/PUT/DELETE /api/clientes/{id}`, `POST /api/clientes/{id}/inativar`, `POST /api/clientes/{id}/reativar` |
| Endereço | `POST/GET /api/clientes/{clienteId}/enderecos`, `GET/PUT/DELETE /api/clientes/{clienteId}/enderecos/{id}` |
| Contato | `POST/GET /api/clientes/{clienteId}/contatos`, `GET/PUT/DELETE /api/clientes/{clienteId}/contatos/{id}` |
| Empresa | `POST/GET /api/clientes/{clienteId}/empresas`, `GET/PUT/DELETE /api/empresas/{id}`, `POST /api/empresas/{id}/inativar`, `POST /api/empresas/{id}/reativar` |
| Filial | `POST/GET /api/empresas/{empresaId}/filiais`, `GET/PUT/DELETE /api/filiais/{id}`, `POST /api/filiais/{id}/inativar`, `POST /api/filiais/{id}/reativar` |
| Sócio | `POST/GET /api/empresas/{empresaId}/socios`, `GET/PUT/DELETE /api/socios/{id}` |

Criar e listar são aninhados no pai. Operar sobre um registro específico usa o caminho plano
quando o pai não faz parte da identidade (empresa, filial e sócio). Endereço e contato ficam
sempre sob o cliente, e um registro de outro cliente é tratado como inexistente (`404`).

## Regras por tabela

### Cliente
- CNPJ válido (dígitos verificadores), normalizado, único (`409`) e **imutável** (`422` se o `PUT`
  trouxer outro).
- Razão social obrigatória. Nome fantasia opcional.
- Excluir cliente que tem empresas, endereços ou contatos dá `409` dizendo quantos são. O caminho
  correto nesse caso é inativar.

### Endereço
- O cliente precisa existir (`404`) e estar **ativo** para receber endereço novo (`422`).
- UF existente (as 27) e CEP com 8 dígitos (aceita `12345-678`).
- O **primeiro** endereço do cliente é sempre o principal, mesmo que o pedido diga `false`.
- No máximo **um** principal por cliente: marcar um como principal rebaixa o outro, na mesma
  transação.
- Um cliente com endereços nunca fica sem principal: rebaixar o principal atual dá `422`
  ("promova outro a principal antes"), e excluir o principal enquanto existirem outros também.
  Excluir o único endereço é permitido.

### Contato
- Mesmas regras de principal e de cliente ativo do endereço.
- Pelo menos um meio de contato: e-mail **ou** telefone (`422`, na criação e no `PUT`).
- E-mail válido, gravado em minúscula. Telefone com 10 ou 11 dígitos (aceita
  `(11) 98765-4321`).

### Empresa
- A empresa é sempre a **matriz**: o CNPJ precisa ter ordem `0001` (dígitos 9 a 12), senão `422`
  explicando que filiais se cadastram em `/api/empresas/{id}/filiais`.
- CNPJ único (`409`) e imutável (`422`). Cliente ativo para criar (`422`).
- Excluir empresa que tem filiais ou sócios dá `409` dizendo quantos são.

### Filial
- A empresa precisa estar **ativa** para receber filial (`422`).
- A filial compartilha a **raiz** do CNPJ (8 primeiros dígitos) com a empresa (`422` se for
  outra), e a ordem não pode ser `0001`, que é a matriz (`422`).
- CNPJ único (`409`) e imutável (`422`); só o nome muda.

### Sócio
- A empresa precisa estar **ativa** para receber sócio (`422`).
- CPF válido, único **dentro da mesma empresa** (`409`; o mesmo CPF pode ser sócio de empresas
  diferentes) e imutável (`422`).
- Participação maior que 0 e até 100, com no máximo 2 casas decimais.
- A **soma** das participações dos sócios de uma empresa não passa de 100% (`422`, a mensagem diz
  quanto ainda está disponível). No `PUT` a soma desconta o próprio sócio.

## Decisões de interpretação

Pontos em que a especificação dava margem e a implementação escolheu a leitura literal. Todos
estão cobertos por teste.

- **"Cliente/empresa ativo" vale só para criar.** Atualizar, inativar e excluir continuam
  permitidos em registro cujo pai está inativo, porque a regra pedida restringe o cadastro novo.
- **Principal e `PUT`.** Como `PUT` é substituição completa e `principal` omitido vale `false`,
  editar o endereço ou contato **principal atual** exige mandar `principal: true`. Para trocar o
  principal, marca-se outro registro como principal.
- **Listar filhos de um pai que não existe** devolve `404`, não lista vazia. Listar filhos de um pai
  inativo continua permitido.
- **Inativar e reativar são idempotentes** (repetir devolve `200`).
- **Ordem das verificações na criação:** campos (`400`), pai existe (`404`), pai ativo (`422`),
  regras do próprio registro (`422`) e, por último, duplicidade (`409`). **Exceção, o sócio:** a
  duplicidade do CPF (`409`) é verificada **antes** da soma das participações (`422`). Um sócio com
  CPF repetido que também estouraria os 100% recebe `409`, que é a causa mais direta.
- **Filtro por razão social** (`GET /api/clientes?razaoSocial=`) é "contém", sem diferenciar
  maiúscula de minúscula, e trata `%` e `_` como texto comum: buscar `100%` não casa `1000`.

## Concorrência

O banco não impede dois "principais" nem soma de participações acima de 100% (são regras entre
linhas), e não sabe que um pai "ativo" pode ser inativado no meio da criação do filho. Por isso as
operações que dependem de uma contagem, de uma soma ou do estado do pai travam a linha do pai até o
fim da transação (`PESSIMISTIC_WRITE`, que no Oracle é `SELECT ... FOR UPDATE`):

| Operação | Linha travada |
|---|---|
| criar, alterar e excluir endereço | cliente |
| criar, alterar e excluir contato | cliente |
| criar empresa | cliente |
| criar filial | empresa |
| criar e alterar sócio | empresa |

Leituras não travam. A API **não usa coluna de versão** (`@Version`). Dois pedidos simultâneos sobre o
mesmo registro (por exemplo o mesmo `DELETE` enviado duas vezes) se resolvem assim: o segundo não
encontra mais a linha, o Hibernate acusa a falha de atualização e a API responde `409`, não `500`.
Esse tratamento está coberto por teste de unidade do handler (`TratadorDeErrosTest`); a corrida real
entre duas threads **não** é testada, por depender de timing.

`Cliente`, `Empresa` e `Filial` usam `@DynamicUpdate`: o `UPDATE` grava só as colunas que mudaram.
Sem isso, um `PUT` que muda a razão social regravava também `ATIVO` com o valor lido antes e
desfazia um "inativar" que tivesse acontecido no meio.

## Limites de texto: bytes ou caracteres

No Oracle, `VARCHAR2(200)` sem a palavra `CHAR` mede o limite em **bytes**, e este banco usa
`AL32UTF8`: letra acentuada ocupa 2 bytes. Uma razão social de 150 letras "ã" tem 300 bytes e
estouraria uma coluna de 200 bytes (`ORA-12899`) mesmo passando em `@Size(max = 200)`, que conta
caracteres. A migração `V4` muda as colunas de texto livre para `CHAR` (`VARCHAR2(200 CHAR)`), e um
teste (`LimitesDeTextoTest`) envia o tamanho máximo em letras acentuadas para cada campo, em todas
as fatias, e exige que um caractere a mais seja recusado com `400`. As colunas só de dígitos e a
sigla da UF (ASCII) não precisam. A `V4` foi escrita depois de o teste **falhar** com
`ORA-12899 (real: 400, máximo: 200)` em seis tabelas.

## Entradas de borda já tratadas

Casos que passavam pela validação e só falhavam no banco (ou viravam `500`). Foram achados por uma
revisão independente do código, e cada um tem teste em `EntradasDeBordaTest`.

- **Espaço Unicode no lugar de CNPJ, CPF ou UF** (por exemplo U+2003). O `@NotBlank` usa `trim()`,
  que não remove esse espaço, e os validadores aceitavam "em branco" como válido, então o valor
  passava, virava texto vazio ao normalizar e estourava no banco. Agora só o "em branco comum" é
  assunto do `@NotBlank`; qualquer outro texto precisa ser um documento ou UF válido (`400`).
- **Participação com zeros à direita.** `"participacao": 33.330` ou `100.000` chegava com 3 casas e o
  `@Digits` recusava. O `SocioRequest` tira os zeros à direita antes de validar, então `33.330`
  vale `33.33`, e `0.001` continua sendo recusado de verdade.
- **E-mail que cresce ao virar minúsculo.** Em alguns alfabetos a minúscula tem mais caracteres (o
  "İ" turco vira dois). O `ContatoRequest` normaliza o e-mail **antes** de validar o `@Size`.
- **Conversão de maiúscula e minúscula** sempre com `Locale.ROOT`, para o resultado não depender do
  idioma da máquina.

## Endurecimento (segunda revisão independente)

Uma segunda revisão, feita por agentes separados do código e sem rodar nada, achou 19 problemas
reais (outros 7 apontados foram refutados por teste ou leitura). Os corrigidos têm teste em
`EndurecimentoTest` e em `DocumentosTest`. Três delas (paginação, `%`/`_` no filtro e formato de
documento) foram conferidas **quebrando a correção de propósito** e vendo os testes falharem
(11 falhas); as demais passam, mas esse teste de "o teste pega a falha" não foi feito para elas.

- **Texto é aparado antes de validar.** Todo texto dos pedidos passa por `strip()` no construtor do
  `Request`, antes do `@NotBlank` e do `@Size`. Por isso `razaoSocial` com 200 letras e um espaço no
  fim é aceita (vira 200), e `razaoSocial` só com espaço Unicode (U+2003) é recusada.
- **Documento só com dígitos e pontuação.** CNPJ e CPF aceitam apenas `0-9 . / - espaço`. Antes,
  `abc11222333000181xyz` passava, porque a validação descartava tudo que não era dígito.
- **Carimbos de tempo em microssegundos.** `criadoEm` e `atualizadoEm` são truncados para a precisão
  da coluna (`TIMESTAMP(6)`), então o `POST` devolve o mesmo valor que o `GET` seguinte.
- **Limite de corpo.** Requisição que declara mais de 64 KiB (`app.limite-corpo-bytes`) recebe `413`
  sem o corpo ser lido.
- **Criar filho não corre contra a inativação do pai** (ver a tabela em Concorrência).

## Limitações conhecidas

Aceitas de propósito e documentadas para não parecerem esquecimento:

- **`@Size` conta unidades UTF-16**, não pontos de código. Um emoji vale 2, então um campo de 200
  caracteres aceita 100 emojis. É o lado seguro: a coluna (`CHAR`) comporta o dobro.
- **Limite de corpo só vale com `Content-Length`.** Corpo em transferência fragmentada (*chunked*) não
  declara tamanho; o teto efetivo é o do leitor de JSON. O limite completo é papel do proxy reverso
  na frente da API.
- **Não há autenticação nem autorização na API ainda.** Qualquer cliente que alcance a porta lê e
  grava tudo. É o próximo passo do projeto (microsserviço de autenticação e schema `LOGIN`).
