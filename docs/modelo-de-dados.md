# Modelo de dados

> **Situação em 18/11/2026.** Os **cadastros** (`EMPRESA`, `FILIAL`, `PESSOA`, `CONTATO`, `ENDERECO`)
> e os **logs** (`LOG_ALTERACAO`, `LOG_ACESSO`) existem no banco e têm API e testes. CPF/CNPJ de
> pessoa, e-mail e telefone são gravados **cifrados** pela API (migração `V6`) e saem **mascarados**
> nas respostas; o disco do banco também é cifrado. O **cadastro de contas do banco** (quem é cada
> conta, o que pode e quando a senha vence) existe no repositório de regras. O **login** da API
> (usuário, senha, token, bloqueios) existe desde 18/11/2026: toda a `/api` exige token. O **2FA**, os
> **aparelhos** e a
> **autorização** (schemas `LOGIN` e `SESSAO`, mais `LOG_SEGURANCA` e `TENTATIVA_LOGIN`) ainda são
> proposta. O desenho, campo a campo, está em [`diagrama-tabelas.html`](diagrama-tabelas.html) (abra
> no navegador); a API está em [`api-e-regras.md`](api-e-regras.md); este texto explica o porquê.

## Visão geral

São 23 tabelas de negócio em 4 schemas por ambiente (mais uma interna, de controle do log), e uma visão de painel de usuários (`V_USUARIO_ACESSOS`, proposta). A separação por schema não é só organização: cada schema
recebe uma permissão diferente, e é isso que limita o estrago se a API ou uma conta for comprometida.

| Schema | O que guarda | Tabelas | O que a conta de execução da API pode fazer |
|---|---|---|---|
| `KAWASHI_ENV_<AMB>` | cadastros | `EMPRESA`, `FILIAL`, `PESSOA`, `CONTATO`, `ENDERECO` | ler e gravar linhas; não cria, altera nem apaga tabela |
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
  (`NATUREZA` `F` ou `J`, com CPF ou CNPJ), porque cliente e fornecedor costumam ser empresas.
  `PARTICIPACAO` só vale para sócio e `CARGO` para colaborador. O documento fica **cifrado** em
  `DOCUMENTO_CIFRADO`, com a impressão digital em `DOCUMENTO_INDICE` (ver "Criptografia e LGPD"). Ele
  é único por empresa e tipo: a mesma pessoa em duas empresas, ou com dois tipos, são linhas separadas.
- **`CONTATO`**: tabela auxiliar, uma linha por e-mail ou telefone, gravado cifrado em
  `VALOR_CIFRADO` (com `VALOR_INDICE`). Há um principal por tipo (um e-mail principal, um telefone
  principal), e o mesmo contato não se repete no mesmo dono (índice único).
- **`ENDERECO`**: tabela auxiliar, um endereço.

`CONTATO` e `ENDERECO` servem a **filial** e a **pessoa** (não à empresa diretamente). Cada linha
tem duas chaves estrangeiras opcionais (`FILIAL_ID`, `PESSOA_ID`) e um `CHECK` que exige
**exatamente uma** preenchida. Assim o banco continua garantindo que o dono existe, o que uma coluna
genérica "tipo do dono + id" não garantiria.

## Login (schema `LOGIN`)

- **`USUARIO`**: o login de uma pessoa. `PESSOA_ID` é único, então é uma relação 1 para 1: cada
  pessoa tem no máximo um usuário. O e-mail é o nome de login (gravado cifrado, com impressão digital
  para a busca, como os outros dados pessoais).
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
- **`V_USUARIO_ACESSOS`** (visão): o painel dos usuários da aplicação, igual ao das contas do banco.
  Uma linha por usuário e empresa, com nome e cargo (da `PESSOA`), o perfil naquela empresa e a
  situação da senha contra os 90 dias. Todo usuário criado aparece aqui sem cadastro à parte.
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

Quem tem DBA conseguiria recalcular a corrente inteira e enganar a view. Para isso existe a **âncora**:
o script `ancorar-log.ps1`, no repositório de regras, guarda no Vault o número e o hash da última
linha, e a conferência compara o banco com o Vault. Alterar o passado passa a exigir mexer nos dois.
No desenvolvimento o Vault roda em memória e usa a mesma credencial do DBA; em produção a âncora
precisa ficar fora do alcance de quem administra o banco.

Estes são os logs da **aplicação**. A trilha de segurança do **banco** (quem conectou, quem criou
tabela, quem recebeu privilégio) já existe e fica no repositório de regras.

## Token de acesso (aplicado em 18/11/2026, com os números "mais rígidos")

Como a API vai saber, a cada pedido, quem está chamando. Cada escolha abaixo tem o porquê.

| Decisão | Escolha | Por quê |
|---|---|---|
| Tipo | **Opaco** (texto aleatório), não JWT | O projeto precisa cortar acesso **na hora**: logout, troca de senha, desligamento. Um JWT vale até vencer, e revogar exige uma lista negra consultada a cada pedido, que é justamente o custo que ele prometia evitar. E a chave que assina o JWT, se vazar, permite forjar qualquer usuário. Custo do opaco: uma consulta indexada por pedido. |
| Formato | `kw_` + 32 bytes aleatórios (base64url, 46 caracteres) | 256 bits não se adivinham. O prefixo deixa ferramentas de detecção de segredos acharem um token vazado em log, chat ou git. |
| No banco | só o SHA-256 (`TOKEN.TOKEN_HASH`) | Quem lê a tabela (DBA, backup vazado) não consegue usar o token. Com 256 bits de entropia, um hash sem chave basta: não há como testar todas as possibilidades. |
| Como chega | só no cabeçalho `Authorization: Bearer` | Nunca na URL (iria parar no log de acesso, no histórico do navegador e em proxies). Sem cookie, a API não fica exposta a CSRF. Token na URL é recusado. |
| Validade | **deslizante de 15 min com teto de 8 h** | Cada uso empurra o vencimento para 15 minutos à frente; parado 15 minutos, vence. O teto (`EXPIRA_ABSOLUTO_EM`) impede que um token roubado e mantido em uso viva para sempre. Sem "refresh token" separado: menos peças, mesma proteção. |
| Renovação no banco | no máximo **uma vez por minuto** | Gravar a cada pedido seria uma escrita por chamada. Atualizando só quando passou um minuto do último uso, o vencimento fica no máximo 1 minuto "atrasado", o que é irrelevante. |
| Senha vencida (90 dias) | login devolve token de escopo `TROCAR_SENHA` | O usuário entra, mas a única coisa que esse token faz é trocar a senha (o resto dá `403`). Mais simples e mais seguro que um código à parte. |
| Revogação | `REVOGADO_EM` + `MOTIVO_REVOGACAO` | Motivos: logout, sair de todos, troca de senha (derruba **todas** as sessões), aparelho removido, limite de sessões, usuário desligado, suspeita. Toda revogação também vai para o `LOG_SEGURANCA`. |
| Sessões simultâneas | no máximo **3** por usuário | A quarta derruba a mais antiga (`LIMITE_SESSOES`). Limita o estrago de uma senha vazada usada em paralelo. |
| Falha | sempre o mesmo `401` | Token inexistente, vencido ou revogado recebem a mesma resposta: quem testa tokens não aprende nada. |
| Log | o token **nunca** é gravado | O `LOG_ACESSO` passa a guardar o id do usuário, não o token. |
| IP | registrado, **não** preso ao token | Celular troca de IP a toda hora. IP de outro país no meio da sessão vira suspeita (etapa de localização). |
| Empresa | um token por empresa (`TOKEN.EMPRESA_ID`) | Entra com a autorização (etapa 4): trocar de empresa emite outro token. |

| Fuso | tudo em **UTC** (`SYS_EXTRACT_UTC(SYSTIMESTAMP)`) | Gravado, comparado e devolvido em UTC (as respostas trazem o "Z"). Comparar a coluna com `SYSTIMESTAMP` faria o Oracle ler a coluna no fuso da sessão: com a API em −05:00, um token vencido valia mais 5 horas (achado pelo teste, corrigido). |
| Hash da senha | **argon2id** (64 MiB, 3 passadas, 4 linhas: RFC 9106) | Recomendado pela OWASP; caro em memória, o que tira a vantagem de quem ataca com placa de vídeo. O hash grava os parâmetros (formato PHC): dá para endurecer depois e regravar no próximo login certo. Custo abaixo do mínimo da OWASP só com uma chave explícita de teste. |
| Senha | mínimo de **12** caracteres, sem senhas conhecidas nem o e-mail, nada de "uma maiúscula e um símbolo" | Linha do NIST SP 800-63B: tamanho e lista de senhas vazadas protegem; regras de composição levam a senhas previsíveis. A nova não repete a atual nem as **5** anteriores. |

**Primeira entrega do login, aplicada** (o suficiente para fechar "a API não tem login"):
- no repositório de regras: schemas `KAWASHI_ENV_<AMB>_LOGIN` e `KAWASHI_ENV_<AMB>_SESSAO` com as
  permissões da tabela do início, e `TENTATIVA_LOGIN` e `LOG_SEGURANCA` no schema de log;
- tabelas `USUARIO`, `HISTORICO_SENHA`, `PARAMETRO_SEGURANCA`, `TOKEN` e `BLOQUEIO_IP`;
- na API: `POST /api/auth/login`, `POST /api/auth/logout`, `POST /api/auth/sair-de-todos` e
  `POST /api/auth/trocar-senha`, e todo o resto de `/api/**` passando a exigir token;
- senha com argon2id, bloqueio por tentativas e por IP, e um script para criar o primeiro usuário.

O primeiro usuário é criado por `scripts/criar-usuario.ps1`: a senha temporária vai para o Vault (não
aparece na tela) e já nasce vencida, então o primeiro login só serve para trocá-la.

**Diferenças em relação ao desenho original**, decididas na construção:
- `PARAMETRO_SEGURANCA` tem a `CHAVE` como chave primária e o `VALOR` numérico, e a API só a **lê**
  (o desenho original deixava a API alterar). Mudança direta no banco vai sozinha para o
  `LOG_SEGURANCA`, com quem mudou, o valor antigo e o novo.
- As tabelas do login são criadas pelo **repositório de regras**, como as de log, e não pela migração
  da API: a conta de migração não enxerga nem os hashes de senha.
- O e-mail do usuário é gravado cifrado (`EMAIL_CIFRADO`) com a impressão digital (`EMAIL_INDICE`).
- `EMAIL_VERIFICADO_EM` e `TOKEN.DISPOSITIVO_ID`/`EMPRESA_ID` entram com a confirmação de e-mail, os
  aparelhos e a autorização.
- `TENTATIVA_LOGIN` guarda a impressão digital do e-mail tentado, nunca o e-mail.

Ficam para a rodada seguinte: 2FA, códigos de recuperação, dispositivos de confiança (com o token
preso ao aparelho, DPoP: um token roubado não serve em outro aparelho), confirmação de e-mail,
esqueci minha senha, aceite de termos, localização suspeita e a âncora do `LOG_SEGURANCA` no Vault. A autorização por perfil e por
empresa (etapa 4) vem depois; até lá, todo usuário logado pode tudo, inclusive revelar CPF.

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
| Token que expira | `TOKEN.EXPIRA_EM`, `EXPIRA_ABSOLUTO_EM`, `REVOGADO_EM`, `MOTIVO_REVOGACAO` | Opaco, 30 minutos deslizantes com teto de 12 horas, no máximo 5 sessões. Logout, troca de senha, desligamento e remoção do aparelho revogam na hora (ver "Token de acesso"). |
| Token nunca guardado | `TOKEN.TOKEN_HASH` | Só o SHA-256. O token existe apenas na resposta do login e no cabeçalho `Authorization`. |
| Senha vencida | `TOKEN.ESCOPO = 'TROCAR_SENHA'` | Passados 90 dias, o login devolve um token que só serve para trocar a senha. |
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
| `PESSOA.DOCUMENTO` e `CONTATO.VALOR` em texto puro (V5) | `DOCUMENTO_CIFRADO` + `DOCUMENTO_INDICE` e `VALOR_CIFRADO` + `VALOR_INDICE` (V6) |

## Etapas

Cada uma com migração, API, testes e esta documentação atualizados:

1. **Cadastros e logs. Feita.** A migração `V5` trocou as tabelas, a API foi reescrita e o schema de
   log foi criado no repositório de regras. A fixture de teste do SEFAZ (que criava `CLIENTE` e
   `CONFIGURACAO_FISCAL`) e os schemas `SEFAZ` e `SIMPLES_NACIONAL` foram removidos de lá: eram só
   material de teste.
2. **Endurecimento. Feita.** Três mudanças, sem tabela nova de negócio:
   - **duas contas de banco**: a de migração (dona das tabelas, só na subida) e a de execução (schema
     `_APP`, só lê e grava linhas). Quem invadir a API não consegue apagar nem alterar tabela;
   - **HTTPS obrigatório** e cabeçalhos de segurança em toda resposta;
   - **âncora do log no Vault**: o último número e hash de `LOG_ALTERACAO` são guardados fora do
     banco, e uma conferência acusa o DBA que alterar o log e recalcular a corrente.
3. **Login. Primeira entrega feita** (18/11/2026): schemas `LOGIN` e `SESSAO` no repositório de regras,
   senha com argon2id, token opaco, bloqueios por conta e por IP, troca obrigatória aos 90 dias e toda
   a `/api` exigindo token (ver "Token de acesso"). **A fazer na segunda entrega:** 2FA, dispositivos. Entram junto
   as proteções contra robôs: desafio de prova de trabalho depois de errar a senha (cabe na
   `CODIGO_TEMPORARIO`, com uma finalidade nova), limite de pedidos por IP em toda a API, campo-isca
   e tempo mínimo no formulário. E mais: 2FA obrigatório para perfil administrador, reconfirmação da
   senha em ações sensíveis, token preso ao aparelho e link "não fui eu" nos avisos.
4. **Autorização. A fazer.** Perfis, permissões e o vínculo do usuário com cada empresa, conferidos em
   toda chamada.
5. **Criptografia de dados pessoais. Feita** (antes do login, para o login já nascer com ela). Três
   camadas, descritas na seção abaixo.
6. **Cadastro de contas do banco. Feito**, no repositório de regras. Ver "Contas do banco" abaixo.

No diagrama, os quadros "Cadastros e logs" e "Contas do banco" mostram tabelas existentes, conferidas
campo a campo contra o Oracle; os quadros de login e de autorização continuam marcados como proposta.

## Contas do banco: quem é, o que pode e validade da senha

Pedido: "cada usuário que for criado ir para uma tabela e lá a gente sabe qual usuário tem qual
liberação, qual cargo e como podemos monitorar o limite de 90 dias de senha, lembrando que isso
também se encaixa aos desenvolvedores". Há dois tipos de usuário, e cada um tem o seu painel:

| Quem | Onde mora | Painel | Estado |
|---|---|---|---|
| contas do **banco**: desenvolvedores, estagiários, infra, contas de API, o administrador | `SYS.KAWASHI_CONTA` (repositório de regras) | `SYS.V_KAWASHI_CONTAS` e `SYS.V_KAWASHI_CONTA_LIBERACOES`; no terminal, `infra/scripts/cadastro-de-contas.ps1` | **feito** |
| usuários da **aplicação** (quem entra na API) | `USUARIO`, `PESSOA`, `USUARIO_EMPRESA` | `V_USUARIO_ACESSOS` | proposta, entra com o login |

Regras de negócio do cadastro de contas do banco:

1. **Toda conta criada entra no cadastro.** `create-team-member.ps1` (com `-Nome` e `-Cargo`) e
   `create-api-account.ps1` (com `-Responsavel`) gravam a linha na mesma execução que cria a conta.
   O administrador e as contas que já existiam entram pela instalação (`12_cadastro_de_contas.sql`).
2. **A tabela guarda só o que gente sabe dizer**: tipo (`PESSOA`, `API`, `ADMIN`), perfil, nome,
   cargo, ambiente (API) e quem criou. **Liberações e datas da senha não são copiadas**: as visões
   leem o dicionário do Oracle na hora. Um `GRANT` dado por fora dos scripts aparece no painel no
   mesmo instante; uma cópia ficaria mentindo.
3. **Conta criada por fora não some**: aparece como `SEM CADASTRO`, com pendência. Conta apagada do
   banco continua no cadastro como `REMOVIDA` (histórico de quem já teve acesso).
4. **Só um caminho de escrita**: a procedure `KAWASHI_REGISTRAR_CONTA`, executável só pelo
   engenheiro. Nem o DBA faz `UPDATE`/`DELETE` direto na tabela, e uma conta comum não lê o painel.
5. **Senha de 90 dias.** O limite vem do `PASSWORD_LIFE_TIME` do perfil `KAWASHI_SECURE_PROFILE`
   (mudou lá, muda aqui). Situação: `EM DIA`, `VENCE EM BREVE` (faltam 15 dias ou menos), `VENCIDA`,
   `TROCA PENDENTE` (conta nova que ainda não fez o primeiro login) e `CONTA BLOQUEADA`.
   - **Pessoa e administrador**: o próprio Oracle bloqueia ao vencer, com 7 dias de tolerância. O
     painel avisa antes.
   - **Conta de API**: a senha **não** expira sozinha, de propósito (uma API parada de madrugada por
     senha vencida é pior). O painel conta os mesmos 90 dias e marca `VENCIDA` para a rotação ser
     feita à mão.
6. **Monitoramento**: `cadastro-de-contas.ps1 -Acao Alertas` lista só as contas com pendência (senha
   vencida ou vencendo, sem cadastro, pessoa sem nome ou cargo, conta bloqueada) e termina com código 1
   quando há alguma. A `rotina-diaria.ps1` roda isso **todo dia às 9h** (Agendador de Tarefas), junto
   com a conferência do log, grava o relatório e avisa no Windows quando há pendência.
7. **Desligamento**: `remove-team-member.ps1 -Username ... -Motivo ...` tira todo acesso de quem saiu
   (tranca a conta, revoga proxies, roles, privilégios e permissões, derruba as sessões abertas,
   inclusive por proxy, e apaga login e segredos no Vault). O cadastro vira `DESLIGADA`, com data,
   quem desligou e o motivo, e sai da lista do dia a dia. Desligada não tem senha cobrada, mas vira
   **alarme** se for destrancada ou receber acesso de volta. A conta fica trancada, não apagada, a
   menos que se peça `-Apagar`; o mesmo nome criado de novo depois é outra conta e volta a `OK`.
   Conta de API, administrador e quem tem `DBA` não são desligados por aqui.

## Criptografia e LGPD (aplicada em 16/11/2026)

Três camadas, cada uma contra um risco diferente:

| Camada | Onde | O que protege | O que não protege |
|---|---|---|---|
| **1. Disco (TDE)** | Oracle; `infra/scripts/enable-tde.ps1` no repositório de regras | quem copia o disco, um arquivo do banco ou um backup: todas as tablespaces do Kawashi e a `USERS` são AES-256, e a tablespace criada no futuro já nasce cifrada | quem está logado no banco |
| **2. Campo, pela API** | `CifraDeDados`, migração `V6` | quem lê a tabela direto (DBA, desenvolvedor com acesso ao ambiente, vazamento por SQL): o banco só vê `v1:...` | quem invade a API com a chave em memória |
| **3. Máscara na leitura** | `Mascaras`, respostas da API | exposição desnecessária em tela, relatório, print ou log de quem consome a API | quem pode chamar o caminho de revelar |

**Camada 2, como funciona.** Campos cifrados: o documento da pessoa (CPF e também CNPJ, por
simplicidade: a coluna é uma só) e o valor do contato (e-mail e telefone). Nome e endereço ficam de
fora, porque precisam de busca e ordenação.

- **AES-256-GCM** com um número aleatório por gravação: o mesmo CPF gravado duas vezes dá duas cifras
  diferentes, e quem olha a tabela não descobre nem que duas linhas são da mesma pessoa. O GCM também
  autentica: cifra alterada, ou copiada de outra coluna, **não decifra** (a API responde 500 sem vazar
  nada, em vez de devolver lixo).
- **Impressão digital (HMAC-SHA256 com outra chave)** ao lado: sempre igual para o mesmo valor. É ela
  que mantém o documento único por empresa e tipo, e o contato único por dono, **no banco**. Um hash
  sem chave não serviria: CPF tem poucas combinações e seria quebrado por tentativa.
- As chaves (`CIFRA_CHAVE_DADOS`, `CIFRA_CHAVE_INDICE`) ficam com a aplicação, nunca no banco.
  `scripts/gerar-chaves-de-cifra.ps1` gera as duas no `.env` e guarda cópia no Vault
  (`kawashi-secrets/cifra/<AMB>`). **Perder a chave de dados é perder os dados.** Sem as chaves, a API
  não sobe.
- O banco ainda confere que as colunas têm cara de cifra (`CHECK`): texto em claro gravado por engano
  é recusado. O que ele deixou de conferir: que o CPF tem 11 dígitos (não vê mais os dígitos); essa
  regra passou a ser só da API.
- Custo aceito: não dá para buscar por pedaço do documento ou do e-mail, só por valor exato.

**Camada 3, como funciona.** As respostas mostram `***.982.247-**`, `f***@exemplo.com.br`,
`(11) *****-4321`. O valor inteiro só sai por `GET /api/pessoas/{id}/documento` e
`GET /api/contatos/{id}/valor`, que ficam no `LOG_ACESSO`: dá para responder "quem viu o documento de
quem, e quando". Quando a autorização existir, esses caminhos ganham uma permissão própria. Para não
obrigar quem só tem a máscara a conhecer o valor inteiro, a atualização aceita a máscara devolvida
como "não mexa no valor"; a máscara de outro valor é recusada, e no cadastro valor mascarado não vale.

Sobre a LGPD (Lei 13.709/2018), com a ressalva de que esta é uma leitura técnica, não um parecer
jurídico:

- A lei **não obriga criptografia nominalmente**; exige "medidas de segurança, técnicas e
  administrativas aptas a proteger os dados pessoais" (art. 46). Criptografia é uma das medidas mais
  aceitas, e dado cifrado reduz a gravidade de um incidente.
- CPF, nome, e-mail, telefone e endereço são **dados pessoais** comuns. A categoria "dado pessoal
  sensível" da lei (saúde, biometria, religião, entre outros) não aparece neste modelo.
- Além de proteger, a lei pede coisas que afetam o desenho e ainda **não estão implementadas**:
  guardar só o necessário, definir prazo de retenção, e atender o direito de eliminação. No sistema
  isso tende a virar **anonimizar** a pessoa (trocar nome e documento por valores neutros) em vez de
  apagar a linha, para não quebrar o histórico e os logs.
- O que o projeto já faz nessa direção: os logs não guardam valor de campo nem query string, só
  "qual registro mudou"; nenhuma senha, token ou código fica em texto puro; `ACEITE_TERMO` (proposto)
  registra o consentimento por versão.
