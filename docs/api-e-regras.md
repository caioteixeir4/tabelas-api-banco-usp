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
  Ordenar por campo que não existe devolve `400`.
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
  regras do próprio registro (`422`) e, por último, duplicidade (`409`).

## Concorrência

O banco não impede dois "principais" nem soma de participações acima de 100% (são regras entre
linhas). Por isso as operações que dependem de uma contagem ou soma travam a linha do pai até o fim
da transação: endereço trava o cliente (`SELECT ... FOR UPDATE`), contato trava o cliente
(`PESSIMISTIC_WRITE`) e sócio trava a empresa (`PESSIMISTIC_WRITE`). Leituras não travam.

## Limites de texto: bytes ou caracteres

No Oracle, `VARCHAR2(200)` sem a palavra `CHAR` mede o limite em **bytes**, e este banco usa
`AL32UTF8`: letra acentuada ocupa 2 bytes. Uma razão social de 150 letras "ã" tem 300 bytes e
estouraria uma coluna de 200 bytes (`ORA-12899`) mesmo passando em `@Size(max = 200)`, que conta
caracteres. A migração `V4` muda as colunas de texto livre para `CHAR` (`VARCHAR2(200 CHAR)`), e um
teste envia o tamanho máximo em letras acentuadas para cada campo, em todas as fatias. As colunas
só de dígitos e a sigla da UF (ASCII) não precisam.
