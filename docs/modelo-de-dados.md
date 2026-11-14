# Modelo de dados

> **Situação em 14/11/2026.** Os **cadastros** (`EMPRESA`, `FILIAL`, `PESSOA`, `CONTATO`, `ENDERECO`)
> e os **logs** (`LOG_ALTERACAO`, `LOG_ACESSO`) existem no banco e têm API e testes. **Login** e
> **autorização** (schemas `LOGIN` e `SESSAO`, mais `LOG_SEGURANCA` e `TENTATIVA_LOGIN`) ainda são
> proposta. O desenho, campo a campo, está em [`diagrama-tabelas.html`](diagrama-tabelas.html) (abra
> no navegador); a API está em [`api-e-regras.md`](api-e-regras.md); este texto explica o porquê.

## Visão geral

São 23 tabelas de negócio em 4 schemas por ambiente (mais uma interna, de controle do log). A separação por schema não é só organização: cada schema
recebe uma permissão diferente, e é isso que limita o estrago se a API ou uma conta for comprometida.

| Schema | O que guarda | Tabelas | O que a conta da API pode fazer |
|---|---|---|---|
| `KAWASHI_ENV_<AMB>` | cadastros | `EMPRESA`, `FILIAL`, `PESSOA`, `CONTATO`, `ENDERECO` | ler e gravar |
| `KAWASHI_ENV_<AMB>_LOGIN` | o que é permanente do usuário e a autorização | `USUARIO`, `HISTORICO_SENHA`, `SEGUNDO_FATOR`, `CODIGO_RECUPERACAO`, `DISPOSITIVO`, `ACEITE_TERMO`, `PERFIL`, `PERMISSAO`, `PERFIL_PERMISSAO`, `USUARIO_EMPRESA`, `PARAMETRO_SEGURANCA` | ler e gravar |
| `KAWASHI_ENV_<AMB>_SESSAO` | o que expira | `TOKEN`, `CODIGO_TEMPORARIO`, `BLOQUEIO_IP` | ler, gravar e apagar o vencido |
| `KAWASHI_ENV_<AMB>_LOG` | histórico | `LOG_ALTERACAO`, `LOG_ACESSO`, `LOG_SEGURANCA`, `TENTATIVA_LOGIN` | **só inserir e ler** |

`<AMB>` é o ambiente (`TESTE_DEV`, `DEV`, `INFRA`, `HOMOLOG`, `PROD`...). Os schemas são criados pelo
repositório `regras-banco-usp`; as tabelas, por este, **com uma exceção: as do schema `LOG`** também
são criadas lá. Quem cria uma tabela é dono dela e consegue apagar as linhas; para "a API só insere
no log" ser uma permissão do banco, e não uma promessa, o dono precisa ser outro.

Regra das chaves estrangeiras entre schemas: **o schema de serviço aponta para o padrão, nunca o
contrário**. `USUARIO` aponta para `PESSOA`, `USUARIO_EMPRESA` e
`TOKEN` apontam para `EMPRESA`; nenhuma tabela de cadastro sabe que o login existe.

Cada tabela existe uma vez só no banco. No diagrama, quando um quadro precisa de uma tabela de outro
(o login precisa da `PESSOA`; a autorização, do `USUARIO` e da `EMPRESA`), ela aparece **repetida em
cinza, com borda tracejada e o aviso "repetida: esta tabela é do quadro de ..."**, mostrando só a
chave. Os campos ficam no quadro de origem.

## Cadastros (schema padrão)

- **`EMPRESA`**: a matriz. CNPJ único.
- **`FILIAL`**: pertence a uma empresa. CNPJ único.
- **`PESSOA`**: pertence a uma empresa. A separação fica na coluna `TIPO`, um enum: `CLIENTE`,
  `COLABORADOR`, `SOCIO`, `FORNECEDOR`. Não existe mais uma tabela para cada papel, nem a tabela
  `CLIENTE`; um tipo novo é um valor novo na lista. A pessoa pode ser física ou jurídica
  (`NATUREZA` `F` ou `J`, com CPF ou CNPJ em `DOCUMENTO`), porque cliente e fornecedor costumam
  ser empresas. `PARTICIPACAO` só vale para sócio e `CARGO` para colaborador. O documento é único
  por empresa e tipo: a mesma pessoa em duas empresas, ou com dois tipos, são linhas separadas.
- **`CONTATO`**: tabela auxiliar, uma linha por e-mail ou telefone. Há um principal por tipo (um
  e-mail principal, um telefone principal).
- **`ENDERECO`**: tabela auxiliar, um endereço.

`CONTATO` e `ENDERECO` servem a **filial** e a **pessoa** (não à empresa diretamente). Cada linha
tem duas chaves estrangeiras opcionais (`FILIAL_ID`, `PESSOA_ID`) e um `CHECK` que exige
**exatamente uma** preenchida. Assim o banco continua garantindo que o dono existe, o que uma coluna
genérica "tipo do dono + id" não garantiria.

## Login (schema `LOGIN`)

- **`USUARIO`**: o login de uma pessoa. `PESSOA_ID` é único, então é uma relação 1 para 1: cada
  pessoa tem no máximo um usuário. O e-mail é o nome de login.
- **`HISTORICO_SENHA`**: hashes das senhas anteriores, para a troca não repetir uma senha recente.
- **`SEGUNDO_FATOR`**: o 2FA por aplicativo autenticador (TOTP). **É opcional**: só existe linha
  para quem ativou; quem não ativou entra só com a senha. Relação 1 para 1 com o usuário.
- **`CODIGO_RECUPERACAO`**: códigos de uso único, entregues ao ativar o 2FA, para quem perder o
  celular.
- **`DISPOSITIVO`**: os aparelhos em que o usuário já entrou. `CONFIAVEL_ATE` é o "dispositivo de
  confiança": até essa data, o login naquele aparelho não pede o 2FA.
- **`ACEITE_TERMO`**: qual versão dos termos de uso e da política de privacidade o usuário aceitou,
  quando e de qual IP. O sistema guarda CPF, e a LGPD pede esse registro. Versão nova, aceite novo.

## Autorização (schema `LOGIN`)

O login diz **quem é** o usuário. Estas tabelas dizem **o que ele pode fazer e em quais empresas**.

- **`PERMISSAO`**: cada ação que a API protege, por código (`PESSOA_LER`, `PESSOA_ALTERAR`,
  `USUARIO_GERENCIAR`...).
- **`PERFIL`**: um conjunto de permissões com nome (`ADMINISTRADOR`, `OPERADOR`, `CONSULTA`).
- **`PERFIL_PERMISSAO`**: quais permissões cada perfil tem (N para N).
- **`USUARIO_EMPRESA`**: em quais empresas o usuário entra e com qual perfil em cada uma. É o
  **multiempresa**: o mesmo usuário pode ser administrador numa empresa e só consulta em outra, e
  sem uma linha aqui ele não enxerga nada daquela empresa.
- **`PARAMETRO_SEGURANCA`**: os números das regras (validade da senha, limite de tentativas, duração
  do token...). Mudam sem publicar a API de novo, e cada mudança gera uma linha em `LOG_SEGURANCA`,
  para que afrouxar uma regra não passe despercebido.

Cada sessão vale para **uma** empresa: `TOKEN.EMPRESA_ID`. A API confere, a cada chamada, a
permissão do perfil que o usuário tem naquela empresa. Para trocar de empresa, emite outro token.

## O que expira (schema `SESSAO`)

Tudo aqui tem data de validade obrigatória e pode ser apagado depois de vencido, sem perder história
(a história está no schema `LOG`).

- **`TOKEN`**: a sessão. Tem duas validades: `EXPIRA_EM`, que se renova enquanto há uso, e
  `EXPIRA_ABSOLUTO_EM`, um teto que a renovação não ultrapassa. Sem o teto, um token roubado e
  mantido em uso viveria para sempre. `REVOGADO_EM` marca o logout. Guarda de qual `DISPOSITIVO`
  veio, para dar para encerrar todas as sessões de um aparelho.
- **`CODIGO_TEMPORARIO`**: código de uso único e validade curta, com uma `FINALIDADE`: redefinir a
  senha ("esqueci minha senha"), confirmar o e-mail, ou a etapa intermediária do 2FA (senha certa,
  aguardando o código). Um código só serve para a finalidade em que nasceu, e `TENTATIVAS` mata o
  código depois de alguns erros, mesmo dentro da validade.
- **`BLOQUEIO_IP`**: IPs barrados por excesso de tentativas.

## Logs (schema `LOG`)

- **`LOG_ALTERACAO`**: quem criou, alterou ou apagou qual registro, e quando. Guarda o nome da
  tabela e o id, sem chave estrangeira, para o log sobreviver à exclusão do registro.
- **`LOG_SEGURANCA`**: tudo que muda a segurança de uma conta: senha trocada, 2FA ligado ou
  desligado, aparelho marcado como confiável, conta bloqueada, perfil alterado, sessão encerrada,
  parâmetro alterado. Responde "quem deu permissão de administrador a esta conta, e quando".
- **`LOG_ACESSO`**: cada chamada à API (quem, de qual IP, método, caminho, status).
- **`TENTATIVA_LOGIN`**: toda tentativa de login, certa ou errada, com IP, país, cidade e a marca
  `SUSPEITA`.

Duas proteções contra adulteração (as duas já valem para `LOG_ALTERACAO`):

1. **A conta da API só insere e lê neste schema.** Sem `UPDATE` nem `DELETE`, nem a própria API (ou
   quem a invadir) consegue alterar ou apagar o histórico. Ninguém loga no schema de log.
2. **Encadeamento por hash** em `LOG_ALTERACAO` (e, quando existir, em `LOG_SEGURANCA`). Um gatilho
   do banco, e não a API, numera cada linha sem buraco (`ORDEM`), carimba a hora do servidor e grava
   o `HASH` da linha junto com o da anterior (`HASH_ANTERIOR`). A tabela interna `LOG_CADEIA` guarda
   o último número e o último hash. Apagar ou alterar uma linha quebra a corrente, e a view
   `V_LOG_ALTERACAO_QUEBRAS` aponta onde (inclusive linhas apagadas no fim).

Limite honesto: quem tem DBA consegue recalcular a corrente inteira. O encadeamento protege contra
adulteração parcial e contra a própria aplicação; contra um DBA mal-intencionado a defesa é a trilha
de auditoria do banco, no repositório de regras.

Estes são os logs da **aplicação**. A trilha de segurança do **banco** (quem conectou, quem criou
tabela, quem recebeu privilégio) já existe e fica no repositório de regras.

## Como cada mecanismo de segurança funciona

| Mecanismo | Tabelas e colunas | Regra (na API) |
|---|---|---|
| Senha nunca guardada | `USUARIO.SENHA_HASH` | Só o hash (argon2id ou bcrypt). |
| Troca de senha a cada 3 meses | `USUARIO.SENHA_ALTERADA_EM`, `HISTORICO_SENHA` | Passados 90 dias, o login só permite trocar a senha. A nova não pode repetir as últimas 5. |
| Bloqueio por tentativas | `USUARIO.TENTATIVAS_FALHAS`, `USUARIO.BLOQUEADO_ATE` | 5 erros seguidos bloqueiam a conta por 15 minutos, mesmo com a senha certa. Zera no acerto. |
| Limite contra robôs | `TENTATIVA_LOGIN`, `BLOQUEIO_IP` | 20 erros do mesmo IP em 10 minutos bloqueiam o IP por 1 hora, mesmo testando e-mails diferentes. |
| 2FA (opcional) | `SEGUNDO_FATOR`, `CODIGO_RECUPERACAO`, `CODIGO_TEMPORARIO` | Quem ativou informa o código do autenticador depois da senha. |
| Dispositivo de confiança | `DISPOSITIVO.CONFIAVEL_ATE` | O aparelho marcado dispensa o 2FA por 30 dias. |
| Localização suspeita | `TENTATIVA_LOGIN.PAIS`, `CIDADE`, `SUSPEITA` | País ou cidade que nunca apareceu nos logins certos do usuário, ou dois logins longe demais para o tempo entre eles. Login suspeito pede o 2FA mesmo em dispositivo de confiança e avisa o usuário por e-mail. |
| Token que expira | `TOKEN.EXPIRA_EM`, `EXPIRA_ABSOLUTO_EM`, `REVOGADO_EM` | Vale 30 minutos, renovado enquanto há uso, com teto de 12 horas. Logout, troca de senha e remoção do aparelho revogam. |
| Token nunca guardado | `TOKEN.TOKEN_HASH` | Só o SHA-256. O token existe apenas na resposta do login. |
| Quem pode o quê | `PERFIL`, `PERMISSAO`, `PERFIL_PERMISSAO` | A API confere a permissão antes de cada operação. |
| Multiempresa | `USUARIO_EMPRESA`, `TOKEN.EMPRESA_ID` | O usuário só enxerga as empresas em que tem vínculo, com o perfil daquele vínculo. |
| Esqueci minha senha | `CODIGO_TEMPORARIO` | Código de uso único, validade curta e limite de tentativas, enviado por e-mail. |
| E-mail confirmado | `USUARIO.EMAIL_VERIFICADO_EM`, `CODIGO_TEMPORARIO` | Sem confirmar o e-mail, não entra. |
| Rastro de mudanças de segurança | `LOG_SEGURANCA` | Toda mudança de senha, 2FA, perfil, bloqueio ou parâmetro é registrada. |
| Log à prova de adulteração | schema `LOG`, `HASH_ANTERIOR`, `HASH` | A API só insere; as linhas são encadeadas por hash. |
| Consentimento (LGPD) | `ACEITE_TERMO` | Aceite por versão dos termos e da política de privacidade. |
| Regras ajustáveis e auditadas | `PARAMETRO_SEGURANCA` | Os números ficam em tabela; cada mudança vai para o `LOG_SEGURANCA`. |

Os números (90 dias, 5 erros, 15 minutos, 20 erros, 1 hora, 30 minutos, 12 horas, 30 dias) são a
proposta inicial e ficam em `PARAMETRO_SEGURANCA`.

Em nenhuma coluna fica senha, token ou código em texto puro. A única exceção é a semente do 2FA
(`SEGUNDO_FATOR.SEGREDO_CIFRADO`): ela precisa ser lida de volta para conferir o código, então é
**cifrada** (não hash), com uma chave que fica no Vault.

### Segurança que não vira tabela

- A mesma resposta para "e-mail não existe" e "senha errada", para ninguém descobrir quais e-mails
  têm conta.
- Atraso crescente a cada erro de senha, além do bloqueio.
- Senha com no mínimo 12 caracteres e recusa de senhas conhecidas de vazamentos.
- Troca de senha derruba todas as sessões.
- Aviso por e-mail em login suspeito, aparelho novo, troca de senha e ativação ou remoção do 2FA.
- Token só por HTTPS, no cabeçalho ou em cookie `HttpOnly` + `Secure` + `SameSite`.
- Um job apaga tokens vencidos, códigos usados e bloqueios expirados do schema `SESSAO`.

### O que ficou de fora de propósito

- **Chaves de API para sistemas externos**: só quando houver uma integração de verdade.
- **Fila de e-mails de aviso**: dá para começar enviando direto.
- **Tabela separada de "refresh token"**: a renovação com teto absoluto resolve com menos peças.

## Decisões

**Tomadas**

- Toda pessoa pertence a uma empresa (`PESSOA.EMPRESA_ID` obrigatório).
- `PESSOA` única com a separação na coluna `TIPO` (enum: cliente, colaborador, sócio, fornecedor),
  em vez de uma tabela por papel. Cliente faz parte das pessoas: a tabela `CLIENTE` acaba.
- Contato e endereço são tabelas auxiliares, de uma pessoa ou de uma filial.
- 2FA opcional: cada usuário escolhe ativar.
- Login, sessão (o que expira) e logs em schemas separados dos cadastros.
- Autorização por perfil e por empresa, log de segurança, código temporário único, teto do token,
  aceite de termos, parâmetros em tabela e encadeamento dos logs.

**Em aberto**

1. Os números do login, listados acima. Como ficam em `PARAMETRO_SEGURANCA`, podem ser ajustados
   depois sem mudar tabela nem publicar a API de novo.

## O que mudou em relação ao primeiro modelo

| Antes (até a V4) | Agora |
|---|---|
| `CLIENTE` | deixou de existir: `PESSOA` com `TIPO = 'CLIENTE'` |
| `SOCIO` | `PESSOA` com `TIPO = 'SOCIO'` e a `PARTICIPACAO` |
| colaborador e fornecedor (não existem) | `PESSOA` com `TIPO = 'COLABORADOR'` (com o `CARGO`) ou `'FORNECEDOR'` |
| `EMPRESA` (de um cliente) | `EMPRESA`, sem `CLIENTE_ID` |
| `FILIAL` | `FILIAL`, igual |
| `CONTATO` (nome, e-mail e telefone de um cliente) | `CONTATO`, uma linha por meio, de uma filial ou de uma pessoa |
| `ENDERECO` (só de cliente) | `ENDERECO` de uma filial ou de uma pessoa |
| sem login na API | schemas `LOGIN` e `SESSAO` |
| sem controle de quem pode o quê | `PERFIL`, `PERMISSAO`, `USUARIO_EMPRESA` |
| sem log na API | schema `LOG` |

## Etapas

Cada uma com migração, API, testes e esta documentação atualizados:

1. **Cadastros e logs. Feita.** A migração `V5` trocou as tabelas, a API foi reescrita e o schema de
   log foi criado no repositório de regras. A fixture de teste do SEFAZ (que criava `CLIENTE` e
   `CONFIGURACAO_FISCAL`) e os schemas `SEFAZ` e `SIMPLES_NACIONAL` foram removidos de lá: eram só
   material de teste.
2. **Login. A fazer.** Cria os schemas `LOGIN` e `SESSAO` no repositório de regras (com as permissões
   da tabela do início) e implementa na API: senha, token, bloqueios, 2FA, dispositivos.
3. **Autorização. A fazer.** Perfis, permissões e o vínculo do usuário com cada empresa, conferidos em toda
   chamada.

No diagrama, o quadro "Cadastros e logs" mostra tabelas existentes, conferidas campo a campo contra o
Oracle; os quadros de login e de autorização continuam marcados como proposta.
