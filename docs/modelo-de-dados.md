# Modelo de dados

> **Situação em 25/11/2026.** Os **cadastros** (`EMPRESA`, `FILIAL`, `PESSOA`, `CONTATO`, `ENDERECO`)
> e os **logs** (`LOG_ALTERACAO`, `LOG_ACESSO`) existem no banco e têm API e testes. CPF/CNPJ de
> pessoa, e-mail e telefone são gravados **cifrados** pela API (migração `V6`) e saem **mascarados**
> nas respostas; o disco do banco também é cifrado. O **cadastro de contas do banco** (quem é cada
> conta, o que pode e quando a senha vence) existe no repositório de regras. O **login** da API existe
> por **passkey** (desde 20/11/2026) e, desde 25/11/2026, também por **senha** com 2FA opcional (app
> autenticador; obrigatório para administrador): toda a `/api` exige token, e só
> o **BFF** (o servidor do site, repositório `bff-banco-usp`) fala com a API. A **autorização
> multiempresa** existe desde 23/11/2026: cada sessão vive numa empresa, perfis por empresa, e o banco
> garante as regras de cargo. O aceite de termos (LGPD) entrou em 23/11/2026; o convite por e-mail (que confirma o e-mail), a confirmação com passkey antes do que é sensível e o bloqueio por bloco IPv6, em 24/11/2026; o login por senha, o 2FA e o computador de confiança, em 25/11/2026. O desenho, campo a campo, está em [`diagrama-tabelas.html`](diagrama-tabelas.html)
> (abra no navegador); a API está em [`api-e-regras.md`](api-e-regras.md); este texto explica o porquê.

## Visão geral

São 27 tabelas de negócio em 4 schemas por ambiente (mais uma interna, de controle do log), e uma visão de painel de usuários (`V_USUARIO_ACESSOS`). A separação por schema não é só organização: cada schema
recebe uma permissão diferente, e é isso que limita o estrago se a API ou uma conta for comprometida.

| Schema | O que guarda | Tabelas | O que a conta de execução da API pode fazer |
|---|---|---|---|
| `KAWASHI_ENV_<AMB>` | cadastros | `EMPRESA`, `FILIAL`, `PESSOA`, `CONTATO`, `ENDERECO` | ler e gravar linhas; não cria, altera nem apaga tabela |
| `KAWASHI_ENV_<AMB>_LOGIN` | o que é permanente do usuário e a autorização | `USUARIO`, `PASSKEY`, `CONVITE`, `CODIGO_RECUPERACAO`, `PARAMETRO_SEGURANCA`, `ADMIN_PLATAFORMA`, `PERMISSAO`, `PERFIL`, `PERFIL_PERMISSAO`, `USUARIO_EMPRESA`, `CONVITE_VINCULO`, `TERMO`, `ACEITE_TERMO` | ler, criar e alterar (parâmetros, permissões, termos e administradores da plataforma: só ler; perfil e aceite: só ler e criar; do usuário, só mudam `ATIVO` e as colunas da senha, do 2FA e dos identificadores, nunca o `HANDLE` nem o e-mail). Nenhum `DELETE` |
| `KAWASHI_ENV_<AMB>_SESSAO` | o que expira | `TOKEN`, `DESAFIO`, `BLOQUEIO_IP`, `DISPOSITIVO_CONFIAVEL` | ler, criar e alterar (do computador de confiança, só o último uso e a revogação); quem apaga o vencido é um job do banco (ainda não o computador de confiança) |
| `KAWASHI_ENV_<AMB>_LOG` | histórico | `LOG_ALTERACAO`, `LOG_ACESSO`, `LOG_SEGURANCA`, `TENTATIVA_LOGIN`, `LOG_PERMISSAO` | **só inserir e ler** (`LOG_PERMISSAO`: só ler; quem grava são os gatilhos) |

`<AMB>` é o ambiente (`TESTE_DEV`, `DEV`, `INFRA`, `HOMOLOG`, `PROD`...). Os schemas são criados pelo
repositório `regras-banco-usp`; as tabelas de cadastro, por este. **As do login, da sessão e do log**
são criadas lá também. Quem cria uma tabela é dono dela e consegue apagar as linhas; para "a API só
insere no log" (e "a API não lê a chave das passkeys pela conta de migração") ser uma permissão do
banco, e não uma promessa, o dono precisa ser outro.

Regra das chaves estrangeiras entre schemas: **o schema de serviço aponta para o padrão, nunca o
contrário**. `USUARIO` aponta para `PESSOA`; `PERFIL`, `USUARIO_EMPRESA` e `TOKEN` apontam para
`EMPRESA`; nenhuma tabela de cadastro sabe que o login existe.

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

A forma mais forte de entrar é a **passkey**: um par de chaves criado pelo próprio aparelho (Windows
Hello, celular, chave de segurança), liberado pela digital, pelo rosto ou por um PIN que nunca sai do
aparelho. O servidor guarda só a chave **pública**; para entrar, o aparelho assina um desafio. Do lado
da passkey, não há segredo no banco que, vazado, sirva para entrar, e ela só funciona no domínio para
o qual foi criada (um site falso não consegue usá-la: imune a phishing). Desde 25/11/2026 a pessoa
também pode entrar com **senha** (e 2FA): ver "Login por senha e 2FA", abaixo.

- **`USUARIO`**: o login de uma pessoa. `PESSOA_ID` é único (1 para 1). O e-mail é gravado cifrado
  (`EMAIL_CIFRADO`, com `EMAIL_INDICE` para a busca). `HANDLE` é o identificador aleatório da conta
  que vai para o aparelho (o e-mail e o id do banco nunca vão). Desde 25/11/2026 (passo 20 do
  repositório de regras), também: `SENHA_HASH` (Argon2id; o banco só aceita esse formato) e
  `SENHA_ALTERADA_EM` (posto pelo gatilho); o 2FA em `TOTP_SEGREDO_CIFRADO` (cifrado pela API),
  `TOTP_ATIVADO_EM` (hora do banco, posta pelo gatilho) e `TOTP_ULTIMO_PASSO` (o mesmo código não vale
  duas vezes); e os identificadores opcionais `NOME_USUARIO` (único) e o celular em `TELEFONE_CIFRADO`
  + `TELEFONE_INDICE` (único), com `LOGIN_POR_TELEFONE`, que o banco só deixa ligar com o 2FA ativo
  (`CK_USUARIO_TELEFONE_2FA`). Toda mudança de senha, de 2FA e de identificador vai sozinha para o
  `LOG_SEGURANCA` (gatilho `USUARIO_CREDENCIAIS_TRG`).
- **`PASSKEY`**: as passkeys de cada usuário (até 10). `CREDENCIAL_ID` é o id que o aparelho dá;
  `DADOS_CREDENCIAL`, a chave pública (e o resto do que o WebAuthn guarda); `CONTADOR`, o contador de
  assinaturas (se voltar, é um aparelho clonado); `BACKUP_ELEGIVEL`/`BACKUP_FEITO` dizem se é uma
  passkey sincronizada (iCloud, Google). `REVOGADA_EM` marca a removida (nada é apagado).
- **`CONVITE`**: como uma conta nasce. O administrador da empresa (pela API), a plataforma (o primeiro
  administrador de uma empresa nova) ou a equipe (o script `criar-usuario.ps1`) cria o usuário com um
  convite de uso único e validade de 24 h; no link, a pessoa cadastra a primeira passkey. `FINALIDADE`
  `NOVA_PASSKEY` é o convite para quem perdeu tudo: ao ser usado, derruba todas as sessões. Do segredo
  do convite, o banco guarda só o SHA-256. Pela API, o segredo vai para o e-mail da própria pessoa
  (`ENVIADO_POR_EMAIL`), nunca para quem pediu; usar esse convite confirma o e-mail
  (`USUARIO.EMAIL_CONFIRMADO_EM`, gravado pelo gatilho). Um convite novo faz os pendentes vencerem.
  No link, a pessoa cria uma senha ou cadastra a passkey. `FINALIDADE` `REDEFINIR_SENHA` (25/11/2026)
  é o link de "esqueci minha senha": vale `REDEFINICAO_SENHA_MINUTOS` (60) e só serve para trocar a
  senha, nunca para cadastrar passkey ou aceitar convite.
- **`CODIGO_RECUPERACAO`**: 10 códigos de uso único, entregues ao aceitar o convite (e quando a
  pessoa pede novos, o que revoga os anteriores). Servem para quem perdeu os aparelhos (ou a senha): o
  código dá um token que **só** permite cadastrar uma passkey nova ou criar uma senha nova. Só o
  SHA-256 fica no banco.
- **`PARAMETRO_SEGURANCA`**: os números das regras (duração do token, limite de sessões, de falhas por
  IP, validade do convite...). A API só lê; mudança feita no banco vai sozinha para o `LOG_SEGURANCA`,
  com quem mudou, o valor antigo e o novo.
- **`TERMO`** (23/11/2026): cada versão publicada dos termos de uso e da política de privacidade, com
  o texto e o SHA-256 dele, calculado pelo banco. Vale a mais recente de cada documento. Publicada,
  não muda nem sai (nem pelo DBA); versão nova só pelo DBA (`publicar-termo.ps1`).
- **`ACEITE_TERMO`** (23/11/2026): qual versão o usuário aceitou, quando (hora do banco) e de qual
  IP; uma linha por usuário e versão, e cada aceite vai sozinho para o `LOG_SEGURANCA`. O sistema
  guarda CPF, e a LGPD pede esse registro. Sem aceitar as versões vigentes, nada de negócio responde.

## Autorização (schema `LOGIN`, aplicada em 23/11/2026)

O login diz **quem é** o usuário. Estas tabelas dizem **o que ele pode fazer e em quais empresas**.
São criadas pelo repositório de regras (`14_autorizacao.sql`), como as do login: quem é dono de uma
tabela pode tudo nela, e o dono aqui é um schema em que ninguém loga.

- **`PERMISSAO`**: cada ação que a API protege, por código (`PESSOA_LER`, `PESSOA_GERENCIAR`,
  `DADO_PESSOAL_REVELAR`, `USUARIO_GERENCIAR`...). Lista fixa: a API só lê. `EXCLUSIVA_ADMIN` marca
  as que só o ADMINISTRADOR pode ter (dar cargo e gerenciar usuários).
- **`PERFIL`**: os cargos **de cada empresa**. Toda empresa nasce com ADMINISTRADOR, OPERADOR e
  CONSULTA, e o administrador pode criar outros. O ADMINISTRADOR é `FIXO`: tem todas as permissões
  sem precisar de linhas, e o banco recusa renomeá-lo, desativá-lo ou dar e tirar permissão dele.
- **`PERFIL_PERMISSAO`**: quais permissões cada perfil tem (N para N). Tirar uma permissão é
  **desativar** a linha: a API não tem `DELETE` em nenhuma tabela da autorização.
- **`USUARIO_EMPRESA`**: em quais empresas o usuário entra e com qual perfil em cada uma. É o
  **multiempresa**: o mesmo usuário pode ser administrador numa empresa e só consulta em outra, e
  sem uma linha ativa aqui ele não enxerga nada daquela empresa. A chave estrangeira do perfil é
  composta, `(PERFIL_ID, EMPRESA_ID)`, para o banco garantir que o perfil é da mesma empresa.
  Quando o acesso veio de um convite de vínculo, `CONVITE_VINCULO_ID` aponta para ele.
- **`CONVITE_VINCULO`** (24/11/2026): o administrador de uma empresa convida quem já tem conta em outra;
  nada muda até a própria pessoa aceitar. Guarda, sem poder mudar depois, quem convidou, quando (hora do
  banco), de qual IP, para qual empresa e cargo, e a resposta (quem, quando, de qual IP). O convite e a
  resposta vão sozinhos para o `LOG_PERMISSAO` e o `LOG_SEGURANCA`.- **`ADMIN_PLATAFORMA`**: a equipe da Kawashi, que cria empresas novas e o primeiro administrador de
  cada uma. Só se concede pelo banco (script do engenheiro), e não dá acesso aos dados das empresas.
- **`LOG_PERMISSAO`** (schema de log): quem mudou o cargo de quem, quando, de qual perfil para qual,
  e cada permissão posta ou tirada. Gravado por gatilho do banco, encadeado por hash.
- **`V_USUARIO_ACESSOS`** (visão): o painel dos usuários da aplicação, como o das contas do banco.
  Uma linha por usuário e empresa, com nome e cargo (da `PESSOA`), o perfil naquela empresa, se está
  ativo, quantas passkeys ativas tem e o último login. Todo usuário aparece aqui sem cadastro à parte.

O que o **banco** garante, mesmo para quem contorna a API (provado no teste caixa-preta
`Test-Autorizacao.ps1`):

| Regra | Como |
|---|---|
| Toda empresa tem pelo menos um administrador ativo | gatilho composto em `USUARIO_EMPRESA`: depois de cada alteração, trava o perfil ADMINISTRADOR da empresa e conta; zero recusa (`ORA-20103`). A trava faz dois administradores que se rebaixam ao mesmo tempo passarem um de cada vez |
| O perfil do vínculo é da mesma empresa | chave estrangeira composta para `UK_PERFIL_ID_EMPRESA` |
| Só o ADMINISTRADOR dá cargo | `EXCLUSIVA_ADMIN` + gatilho de `PERFIL_PERMISSAO` (`ORA-20108`) |
| O ADMINISTRADOR não se edita | gatilhos de `PERFIL` e `PERFIL_PERMISSAO` (`ORA-20101`, `ORA-20107`); no máximo um fixo por empresa (índice único) |
| Quem fez cada mudança fica registrado | a API é obrigada a informar `USUARIO:<id>` ou `SCRIPT:<nome>` (`ORA-20104` senão); direto no banco, vale a conta de quem está conectado |
| Nenhuma troca passa sem log | os gatilhos gravam no `LOG_PERMISSAO`; a API nem tem `INSERT` nele |
| Ninguém se promove pela API | a API só lê `ADMIN_PLATAFORMA` e `PERMISSAO` |

Cada sessão vale para **uma** empresa (`TOKEN.EMPRESA_ID`). A API confere, a cada chamada, a
permissão do perfil que o usuário tem naquela empresa (lida do banco a cada pedido: trocar o cargo
vale na hora) e que o registro pedido é da empresa da sessão (senão, 404). Para trocar de empresa,
emite outro token. Os detalhes da API estão em "Autorização", em [`api-e-regras.md`](api-e-regras.md).

**Decisões desta etapa, com o Caio:** empresa nova só pelo administrador da plataforma; perfis
editáveis por empresa, mas só o administrador dá cargo, e toda empresa sempre com pelo menos um;
log próprio das trocas (quem, quando, de quê para quê); sessão presa a uma empresa; revelar CPF,
e-mail e telefone com permissão própria.
## O que expira (schema `SESSAO`)

Tudo aqui tem data de validade obrigatória. Quem apaga o vencido é um job do banco
(`KAWASHI_LIMPEZA_SESSAO_JOB`, todo dia às 3h30), não a API: a história está no schema `LOG`.

- **`TOKEN`**: a sessão. Tem duas validades: `EXPIRA_EM`, que se renova enquanto há uso, e
  `EXPIRA_ABSOLUTO_EM`, um teto que a renovação não ultrapassa. `PASSKEY_ID` diz com qual passkey
  entrou (remover a passkey derruba as sessões dela). `ESCOPO` é `COMPLETO`, `CADASTRAR_PASSKEY`
  (entrou com código de recuperação) ou `CONFIGURAR_2FA` (administrador que entrou com senha e ainda
  não tem 2FA: só consegue ativá-lo). `REVOGADO_EM` e `MOTIVO_REVOGACAO` marcam o fim. `VERIFICADO_EM`
  é a última vez que a pessoa se confirmou na sessão (passkey, ou senha e código): operação sensível
  exige que seja recente (`CONFIRMACAO_MINUTOS`). Apagado 7 dias depois do teto.
- **`DESAFIO`**: o número aleatório que o aparelho assina, de uso único e 5 minutos de validade, com
  a `FINALIDADE` (`LOGIN`, `CADASTRO`, `CONFIRMACAO` ou `SEGUNDO_FATOR`; menos o de login, todos são
  presos ao usuário). Usado ou vencido, não serve: uma assinatura capturada não pode ser
  reapresentada. O `SEGUNDO_FATOR` é o passo entre a senha certa e o código do 2FA. Apagado 1 dia
  depois de vencer.
- **`BLOQUEIO_IP`**: IPs barrados por excesso de falhas (o IP é o do navegador, que o BFF informa).
- **`DISPOSITIVO_CONFIAVEL`** (25/11/2026): o computador em que a pessoa marcou "confiar neste
  computador", que dispensa o código do 2FA da mesma conta por `DISPOSITIVO_CONFIAVEL_DIAS` (30). Só o
  SHA-256 do segredo (`TOKEN_HASH`; o segredo fica num cookie `HttpOnly` do BFF), com `NOME`,
  `IP_ORIGEM`, `CRIADO_EM`, `EXPIRA_EM`, `ULTIMO_USO_EM` e `REVOGADO_EM`. O gatilho
  `DISPOSITIVO_CONFIAVEL_TRG` impede, até para o DBA, trocar o dono, o segredo, a origem, a criação ou
  o prazo, e desrevogar (`ORA-20115`); confiar e revogar vão sozinhos para o `LOG_SEGURANCA`. A API não
  apaga: só grava o último uso e a revogação. (O job de limpeza ainda não apaga os vencidos desta
  tabela.)

## Logs (schema `LOG`)

- **`LOG_ALTERACAO`**: quem criou, alterou ou apagou qual registro, e quando. Guarda o nome da
  tabela e o id, sem chave estrangeira, para o log sobreviver à exclusão do registro.
- **`LOG_SEGURANCA`**: tudo que muda a segurança de uma conta: login, logout, passkey cadastrada ou
  removida, códigos gerados ou usados, convite criado ou usado, IP bloqueado, contador suspeito,
  parâmetro alterado; desde 25/11/2026 também senha alterada, 2FA ativado ou desativado,
  identificador alterado, computador de confiança marcado ou revogado, conta bloqueada e confirmação
  com senha. Responde "quem cadastrou uma passkey nesta conta, e quando".
- **`LOG_ACESSO`**: cada chamada à API (quem, de qual IP, método, caminho, status).
- **`TENTATIVA_LOGIN`**: toda tentativa (passkey, senha, código do 2FA, convite, código de
  recuperação, confirmação), certa ou errada, com o IP e o **motivo** da recusa, que o usuário nunca
  vê. É também dela que sai o bloqueio da senha da conta (as falhas recentes daquele usuário).

Duas proteções contra adulteração (valem para `LOG_ALTERACAO` e `LOG_SEGURANCA`):

1. **A conta da API só insere e lê neste schema.** Sem `UPDATE` nem `DELETE`, nem a própria API (ou
   quem a invadir) consegue alterar ou apagar o histórico. Ninguém loga no schema de log.
2. **Encadeamento por hash.** Um gatilho do banco, e não a API, numera cada linha sem buraco
   (`ORDEM`), carimba a hora do servidor e grava o `HASH` da linha junto com o da anterior
   (`HASH_ANTERIOR`). A tabela interna `LOG_CADEIA` guarda o último número e o último hash. Apagar ou
   alterar uma linha quebra a corrente, e as views `V_LOG_ALTERACAO_QUEBRAS` e
   `V_LOG_SEGURANCA_QUEBRAS` apontam onde (inclusive linhas apagadas no fim).

Quem tem DBA conseguiria recalcular a corrente inteira e enganar a view. Para isso existe a **âncora**:
o script `ancorar-log.ps1`, no repositório de regras, guarda no Vault o número e o hash da última
linha do `LOG_ALTERACAO`, e a conferência compara o banco com o Vault. Alterar o passado passa a
exigir mexer nos dois. (A âncora do `LOG_SEGURANCA` ainda não existe.) No desenvolvimento o Vault roda
em memória e usa a mesma credencial do DBA; em produção a âncora precisa ficar fora do alcance de quem
administra o banco.

Estes são os logs da **aplicação**. A trilha de segurança do **banco** (quem conectou, quem criou
tabela, quem recebeu privilégio) já existe e fica no repositório de regras.

## Login por passkey e BFF (aplicado em 20/11/2026)

Substituiu o login por senha (argon2id) que existiu de 18 a 20/11/2026. A pergunta foi "qual é a
arquitetura mais segura para um site no navegador?", e a resposta tem duas partes: **passkey** no
lugar da senha e um **BFF** entre o navegador e a API. Em 25/11/2026 a senha voltou, **ao lado** da
passkey e não no lugar dela (ver "Login por senha e 2FA"); o que está abaixo continua valendo para a
passkey, que segue sendo a forma mais forte de entrar.

### Por que passkey e não senha (mais 2FA)

| Ameaça | Com senha | Com passkey |
|---|---|---|
| Phishing (site falso) | a pessoa digita a senha no site falso | o aparelho só assina para o domínio verdadeiro (`rpId`) e o servidor confere a origem |
| Vazamento do banco | hashes podem ser quebrados offline | só há chaves **públicas**: não servem para entrar |
| Senha repetida de outro site | entra | não existe senha |
| Robô testando credenciais | precisa de bloqueio por conta (que também vira negação de serviço) | não há o que adivinhar; sobra o bloqueio por IP |
| Segundo fator | precisa de 2FA à parte | a passkey já é dois fatores: o aparelho (ter) e a digital/PIN (ser/saber), com `userVerification: required` |

Por isso caíram, junto com a senha: `HISTORICO_SENHA`, troca obrigatória aos 90 dias, bloqueio da
conta por tentativas, `SEGUNDO_FATOR`, `DISPOSITIVO` ("aparelho de confiança") e `CODIGO_TEMPORARIO`
("esqueci minha senha"). Ficam o convite (como a conta nasce) e os códigos de recuperação (como volta
quem perdeu os aparelhos). Com a volta da senha (25/11/2026), parte disso voltou de outra forma, e
mais enxuta: o bloqueio da senha da conta, o 2FA em colunas do `USUARIO`, o
`DISPOSITIVO_CONFIAVEL` e o "esqueci minha senha" como um convite `REDEFINIR_SENHA`. Histórico de
senhas e troca obrigatória continuam de fora.

### Por que BFF

Num site, o token guardado no navegador (`localStorage`) pode ser lido por qualquer script que rode
na página: uma biblioteca comprometida ou uma falha de XSS levam o token embora. Com o **BFF**
(Backend For Frontend, repositório `bff-banco-usp`):

- o navegador fala só com o BFF, que serve o site e guarda o token na **sessão do servidor**. O
  navegador recebe um cookie `__Host-kw_sessao` com `HttpOnly` (o JavaScript não lê), `Secure`,
  `SameSite=Strict` e sem `Domain`;
- a API só atende o BFF: todo pedido precisa do segredo compartilhado (`X-Kawashi-Bff`). Quem achar o
  endereço da API não consegue nem pedir um desafio de login. Em produção, a API também fica numa rede
  que só o BFF enxerga;
- o BFF tem CSP rígida (nenhum script inline nem de outro domínio), proteção contra CSRF (cabeçalho
  próprio + conferência da origem) e repassa à API só uma lista fechada de caminhos.

O preço é um salto a mais por pedido e uma peça a mais para manter. E o IP: como todo pedido chega do
BFF, ele informa o IP do navegador (`X-Kawashi-Ip-Cliente`), e a API só acredita nesse cabeçalho
depois de conferir o segredo.

### As decisões, uma a uma

| Decisão | Escolha | Por quê |
|---|---|---|
| Tipo de token | **Opaco** (texto aleatório), não JWT | O projeto precisa cortar acesso **na hora**: logout, remoção de passkey, desligamento. Um JWT vale até vencer, e revogar exige uma lista negra consultada a cada pedido. E a chave que assina o JWT, se vazar, permite forjar qualquer usuário. Custo do opaco: uma consulta indexada por pedido. |
| Formato | `kw_` + 32 bytes aleatórios (base64url) | 256 bits não se adivinham. O prefixo deixa ferramentas de detecção de segredos acharem um token vazado. |
| No banco | só o SHA-256 (`TOKEN.TOKEN_HASH`) | Quem lê a tabela (DBA, backup vazado) não consegue usar o token. O mesmo para convites e códigos de recuperação. |
| Onde fica | **no BFF**; entre BFF e API, só no cabeçalho `Authorization: Bearer` | O navegador nunca vê o token. Token na URL é recusado (iria parar em log e histórico). |
| Validade | **deslizante de 15 min com teto de 8 h** | Parado 15 minutos, vence; o teto impede que um token roubado e mantido em uso viva para sempre. Sem "refresh token": menos peças, mesma proteção. A renovação é gravada no máximo uma vez por minuto. |
| Sessões simultâneas | no máximo **3** por usuário | A quarta derruba a mais antiga (`LIMITE_SESSOES`). |
| Revogação | `REVOGADO_EM` + `MOTIVO_REVOGACAO` | Logout, sair de todos, passkey removida (cai só a sessão dela), limite de sessões, usuário desligado, recuperação (cai tudo), suspeita. |
| Falha | sempre a mesma resposta | Credencial desconhecida, assinatura errada, desafio usado, usuário desligado: o mesmo `401`. O motivo fica em `TENTATIVA_LOGIN`. |
| Desafio | aleatório, uso único, 5 min, no banco | Sem ele, uma assinatura capturada poderia ser reapresentada. Consumido de forma atômica (um `UPDATE ... WHERE USADO_EM IS NULL`). |
| Verificação | biblioteca **WebAuthn4J** | Criptografia de verificação não se escreve à mão. Confere assinatura, origem, `rpId`, desafio, presença e verificação da pessoa, e o contador. |
| Atestação | `none` | Não restringimos marca de aparelho: aceitar qualquer autenticador que siga o padrão. |
| Passkey "descobrível" | `residentKey: required`, login sem lista de credenciais | O aparelho mostra as contas que tem; o site não precisa perguntar o e-mail (e não revela quais e-mails têm conta). |
| Contador | se voltar, recusa e registra `CONTADOR_SUSPEITO` | Sinal de aparelho clonado. Passkeys sincronizadas mandam sempre 0, e isso é aceito. |
| Recuperação | 10 códigos de uso único + convite novo do administrador | O código só dá um token de escopo `CADASTRAR_PASSKEY` e derruba as outras sessões. Sem códigos, o administrador emite um convite `NOVA_PASSKEY`. Para a passkey não há recuperação por e-mail: o e-mail viraria a porta mais fraca. Por isso o "esqueci minha senha" (25/11/2026) só manda o link para conta que já tem senha. |
| Convite | uso único, 24 h, segredo depois do `#` no link | O que vem depois do `#` o navegador nunca manda ao servidor (não cai em log de acesso nem em proxy); a página o tira da barra de endereço ao ler. O segredo vai no **corpo** dos pedidos, nunca no caminho. |
| Bloqueio por IP | 10 falhas em 10 min bloqueiam o IP por 1 h | Vale para qualquer cerimônia (login, convite, código). Na passkey não há bloqueio por conta: não há senha para adivinhar, e bloquear a conta só serviria para um atacante trancar a vítima do lado de fora. O bloqueio por conta que existe desde 25/11/2026 vale só para a senha, e não impede entrar com passkey. |
| Fuso | tudo em **UTC** (`SYS_EXTRACT_UTC(SYSTIMESTAMP)`) | Gravado, comparado e devolvido em UTC (as respostas trazem o "Z"). Comparar a coluna com `SYSTIMESTAMP` faria o Oracle ler a coluna no fuso da sessão: com a API em −05:00, um token vencido valia mais 5 horas (achado pelo teste, corrigido). |
| Empresa | um token por empresa (`TOKEN.EMPRESA_ID`) | Trocar de empresa emite outro token e revoga o anterior (ver "Autorização"). |

**Diferenças em relação ao desenho original:**
- `PARAMETRO_SEGURANCA` tem a `CHAVE` como chave primária e o `VALOR` numérico, e a API só a **lê**.
- As tabelas do login são criadas pelo **repositório de regras**, como as de log, e não pela migração
  da API: a conta de migração não enxerga nem os e-mails nem as chaves das passkeys.
- `TENTATIVA_LOGIN` não guarda e-mail (nem a impressão digital dele): no login por passkey o e-mail
  nem é informado. No login por senha, o identificador digitado também não é guardado; fica só o
  usuário, quando a conta existe.

Fica para depois: localização suspeita. O aceite de termos e a âncora do
`LOG_SEGURANCA` (e do `LOG_PERMISSAO`) no Vault entraram em 23/11/2026. A criação de usuário pela API entrou com a autorização (etapa 4).

## Login por senha e 2FA (aplicado em 25/11/2026)

**Por que voltou a senha.** Num computador sem Windows Hello e sem Bluetooth, a janela de passkey do
Windows só oferecia uma chave USB: entrar ficava difícil demais. A passkey continua disponível e
continua sendo a opção mais forte; a senha entra ao lado, com as defesas que a tabela "Por que passkey"
mostra que ela precisa (2FA, bloqueio por conta, hash lento). As colunas e a tabela nova vêm do passo
`20_login_por_senha.sql` do repositório de regras; as rotas estão em "Login por senha", em
[`api-e-regras.md`](api-e-regras.md).

| Decisão | Escolha | Por quê |
|---|---|---|
| Identificador | e-mail; ou nome de usuário (opcional); ou celular (só com "login pelo celular" ligado, e o banco só deixa ligar com 2FA) | O celular não é verificado por SMS: o 2FA é a garantia de quem o informou. Identificador só com dígitos é celular, por isso o nome de usuário nunca é só números. |
| Hash | **Argon2id** (Bouncy Castle), 19456 KiB, 2 passadas, 1 fio, sal de 16 bytes, formato PHC | São os parâmetros mínimos da OWASP. Lento e caro em memória de propósito: um vazamento do banco não vira senha quebrada em massa. O banco recusa qualquer outro formato (`CK_USUARIO_SENHA`), então texto em claro não entra nem por engano. |
| Regras da senha | 10+ caracteres (`SENHA_MIN_CARACTERES`), até 200, fora das mais comuns, mais de 2 caracteres diferentes, sem o e-mail nem pedaços do nome | Barra as senhas que um atacante tenta primeiro. |
| Recusa | sempre a mesma; conta inexistente também gasta um Argon2 | Nem a mensagem nem o tempo da resposta dizem se a conta existe. |
| Bloqueio por conta | 5 erros (de senha ou do código) em 15 min param a senha da conta (`CONTA_MAX_FALHAS`, `CONTA_BLOQUEIO_MINUTOS`) | Contra quem testa senhas de muitos IPs. As recusas pelo próprio bloqueio não contam (senão ele nunca acabaria), só contam as falhas depois da última troca de senha, e a passkey continua entrando: o atacante não tranca a vítima do lado de fora. O dono recebe aviso por e-mail. |
| 2FA | app autenticador (TOTP, RFC 6238), opcional, recomendado no site; **obrigatório para administrador** que entra com senha | Administrador sem 2FA recebe uma sessão `CONFIGURAR_2FA`, que só ativa o 2FA. A passkey já é dois fatores, então quem entra com ela não é obrigado. |
| Segredo do 2FA | 20 bytes aleatórios, cifrado pela API (AES-GCM) como os dados pessoais | Quem lê a tabela não gera códigos. O QR code é gerado na API (ZXing) e desenhado pelo site em SVG: o segredo não passa por serviço de imagem de fora. |
| Código repetido | `TOTP_ULTIMO_PASSO`, gravado com `UPDATE` condicional | O mesmo código nunca vale duas vezes, nem com dois pedidos ao mesmo tempo. O relógio é o do banco. |
| Computador de confiança | `DISPOSITIVO_CONFIAVEL`, 30 dias, só o SHA-256 do segredo `kwd_`; o segredo num cookie `HttpOnly` do BFF | Para não pedir o código a cada login no computador de sempre. Dispensa só o código, e só para a mesma conta. Cai tudo ao trocar a senha, desligar o 2FA ou "sair de todos os aparelhos". |
| Esqueci minha senha | convite `REDEFINIR_SENHA` (60 min, no máximo um por minuto), só para conta que já tem senha; com 2FA, pede o código | Numa conta só com passkey, o e-mail viraria um jeito de contornar a passkey. Redefinir derruba todas as sessões e os computadores de confiança. O link não cadastra passkey nem aceita convite. |
| Trocar a senha | pede a atual (ou confirmação recente, para quem só tinha passkey) | Uma sessão roubada não troca a senha. As outras sessões caem e o dono é avisado. |

**O que o banco garante** (passo 20, provado no teste caixa-preta `Test-LoginPorSenha.ps1`): o formato
do hash, do nome de usuário e das colunas cifradas; nome de usuário e celular únicos; login pelo
celular só com 2FA; a hora da troca de senha e da ativação do 2FA posta pelo gatilho; toda mudança de
senha, de 2FA e de identificador no `LOG_SEGURANCA`; e o computador de confiança que não muda de dono,
segredo nem prazo e não volta depois de revogado, nem pelo DBA (`ORA-20115`). A API só altera do
`USUARIO` as colunas da senha, do 2FA, dos identificadores e `ATIVO` (nunca o `HANDLE` nem o e-mail), e
não apaga computador de confiança.

## Como cada mecanismo de segurança funciona

| Mecanismo | Tabelas e colunas | Regra |
|---|---|---|
| Sem segredo que sirva para entrar (passkey) | `PASSKEY.DADOS_CREDENCIAL` | Só a chave pública. A privada nunca sai do aparelho. |
| Senha difícil de quebrar | `USUARIO.SENHA_HASH` | Argon2id com os parâmetros mínimos da OWASP e sal aleatório; o banco só aceita esse formato. |
| Imune a phishing (passkey) | (WebAuthn: `rpId` e origem) | O aparelho só assina para o domínio verdadeiro; a API confere a origem. |
| Dois fatores na passkey | (WebAuthn: `userVerification`) | O aparelho (ter) e a digital, o rosto ou o PIN (ser/saber). Resposta sem a verificação da pessoa é recusada. |
| Segundo fator da senha | `USUARIO.TOTP_SEGREDO_CIFRADO`, `TOTP_ATIVADO_EM`, `TOTP_ULTIMO_PASSO` | App autenticador, opcional; obrigatório para administrador (sessão `CONFIGURAR_2FA` até ativar). O mesmo código não vale duas vezes. |
| Computador de confiança | `DISPOSITIVO_CONFIAVEL` | Só o hash do segredo; 30 dias; dispensa só o código, da mesma conta; revogável, e não volta depois de revogado. |
| Bloqueio da senha da conta | `TENTATIVA_LOGIN` | 5 erros de senha ou código em 15 minutos; a passkey continua entrando. |
| Reapresentação impossível | `DESAFIO` | Uso único, 5 minutos. |
| Aparelho clonado | `PASSKEY.CONTADOR` | Contador que volta: recusa e `CONTADOR_SUSPEITO`. |
| Conta nasce por convite | `CONVITE` | Uso único, 24 h, só o hash no banco. |
| Recuperação | `CODIGO_RECUPERACAO`, `CONVITE` (`NOVA_PASSKEY`, `REDEFINIR_SENHA`) | Código: só cadastra passkey nova ou cria senha nova, e derruba as outras sessões. Sem código: convite do administrador, que também derruba tudo. Esqueci minha senha: link por e-mail só para quem já tem senha, que derruba todas as sessões. |
| Limite contra robôs | `TENTATIVA_LOGIN`, `BLOQUEIO_IP` | 10 falhas do mesmo IP em 10 minutos bloqueiam o IP por 1 hora. |
| Token que expira | `TOKEN.EXPIRA_EM`, `EXPIRA_ABSOLUTO_EM`, `REVOGADO_EM`, `MOTIVO_REVOGACAO` | 15 minutos deslizantes, teto de 8 horas, no máximo 3 sessões. |
| Token nunca guardado em claro | `TOKEN.TOKEN_HASH` | Só o SHA-256. O token só existe no BFF e no cabeçalho entre o BFF e a API. |
| Token fora do navegador | (BFF) | O navegador tem só o cookie de sessão `HttpOnly`. |
| Quem pode o quê | `PERFIL`, `PERMISSAO`, `PERFIL_PERMISSAO` | Toda rota tem uma regra (`@Exige`); rota sem regra é recusada. |
| Multiempresa | `USUARIO_EMPRESA`, `TOKEN.EMPRESA_ID` | A sessão vive numa empresa; registro de outra empresa responde 404. |
| Só o administrador dá cargo | `PERMISSAO.EXCLUSIVA_ADMIN`, `PERFIL.FIXO` | O banco recusa a permissão exclusiva em outro perfil. |
| Empresa sempre com administrador | gatilho de `USUARIO_EMPRESA` | O banco recusa deixar a empresa sem administrador ativo. |
| Rastro das trocas de cargo | `LOG_PERMISSAO` | Gatilho grava quem, quando, de quê para quê; encadeado por hash. |
| Rastro de mudanças de segurança | `LOG_SEGURANCA` | Login, passkeys, senha, 2FA, identificadores, computadores de confiança, códigos, convites, bloqueios e parâmetros. Encadeado por hash. |
| Log à prova de adulteração | schema `LOG`, `HASH_ANTERIOR`, `HASH` | A API só insere; as linhas são encadeadas por hash. |
| Consentimento (LGPD) | `TERMO`, `ACEITE_TERMO` | Aceite por versão dos termos e da política de privacidade, com o hash do texto, a hora e o IP; sem aceite, nada de negócio. |
| Infra não vê linha de negócio | VPD nas tabelas de negócio (repositório de regras, `19_vpd_infra.sql`) | Para quem é do time de Infra, o Oracle filtra toda consulta e alteração em `EMPRESA`, `FILIAL`, `PESSOA`, `CONTATO` e `ENDERECO` (`1 = 0`). A API e o Desenvolvimento não mudam. |
| Quem deu cada acesso | `CONVITE_VINCULO`, `USUARIO_EMPRESA.CONVITE_VINCULO_ID` | Quem convidou, quando e de onde, sem poder mudar; o acesso aponta para o convite. |
| Regras ajustáveis e auditadas | `PARAMETRO_SEGURANCA` | Os números ficam em tabela; cada mudança vai para o `LOG_SEGURANCA`. |

Os números (15 minutos, 8 horas, 3 sessões, 10 falhas, 10 minutos, 1 hora, 5 minutos de desafio, 24 h
de convite, 10 códigos, 10 passkeys; e, desde 25/11/2026, 10 caracteres de senha, 5 falhas e 15
minutos de bloqueio da conta, 30 dias de computador de confiança, 60 minutos do link de senha nova)
ficam em `PARAMETRO_SEGURANCA`, que tem 16 parâmetros.

Em nenhuma coluna fica senha, token, convite, código ou segredo do 2FA em texto puro: a senha é um
hash Argon2id, o segredo do 2FA é cifrado, e o resto é SHA-256.

### Segurança que não vira tabela

- A mesma resposta para toda recusa, para ninguém descobrir quais contas existem.
- Cookie de sessão do BFF com `HttpOnly`, `Secure`, `SameSite=Strict` e prefixo `__Host-`; a sessão
  troca de identificador no login (contra fixação de sessão).
- CSP rígida no site, proteção contra CSRF e lista fechada de caminhos no BFF.
- A API só atende quem tem o segredo do BFF.
- Um job do banco apaga tokens, desafios e bloqueios vencidos.

### O que ficou de fora de propósito

- **Histórico de senhas e troca obrigatória da senha**: a senha voltou em 25/11/2026 sem eles (ver
  "Login por senha e 2FA").
- **2FA por SMS**: o segundo fator é só o app autenticador.
- **Chaves de API para sistemas externos**: só quando houver uma integração de verdade.
- **Tabela separada de "refresh token"**: a renovação com teto absoluto resolve com menos peças.

## Decisões

**Tomadas**

- Toda pessoa pertence a uma empresa (`PESSOA.EMPRESA_ID` obrigatório).
- `PESSOA` única com a separação na coluna `TIPO` (enum: cliente, colaborador, sócio, fornecedor),
  em vez de uma tabela por papel. Cliente faz parte das pessoas: a tabela `CLIENTE` acaba.
- Contato e endereço são tabelas auxiliares, de uma pessoa ou de uma filial.
- Login por passkey (a forma mais forte) ou por senha com 2FA (app autenticador, obrigatório para
  administrador); conta nascida de convite, recuperação por códigos ou convite novo, e "esqueci minha
  senha" só para quem já tem senha.
- Site no navegador com BFF: o token não chega ao navegador, e a API só atende o BFF.
- Login, sessão (o que expira) e logs em schemas separados dos cadastros.
- Autorização por perfil e por empresa, log de segurança, teto do token, aceite de termos,
  parâmetros em tabela e encadeamento dos logs.

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
| sem login na API | schemas `LOGIN` e `SESSAO`, login por passkey ou senha (com 2FA) |
| sem controle de quem pode o quê | `PERFIL`, `PERMISSAO`, `USUARIO_EMPRESA`, `ADMIN_PLATAFORMA` |
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
3. **Login. Feita.** Primeira versão com senha (18/11/2026), trocada no dia 20/11/2026 por **passkey +
   BFF** (ver "Login por passkey e BFF"): schemas `LOGIN` e `SESSAO` no repositório de regras, convite,
   códigos de recuperação, token opaco guardado no BFF, bloqueio por IP e toda a `/api` exigindo token.
   Depois vieram (23/11/2026) o aceite de termos, a criação de usuário pela API, o limite de pedidos
   por IP e a âncora do `LOG_SEGURANCA`. Em 24/11/2026, o convite por e-mail (e com ele a confirmação do e-mail), a confirmação com passkey
   antes do que é sensível e o bloqueio por bloco IPv6. Em 25/11/2026, a senha voltou ao lado da
   passkey (ver "Login por senha e 2FA"): 2FA por app autenticador, obrigatório para administrador,
   computador de confiança, bloqueio da senha por conta, "esqueci minha senha" e identificadores
   opcionais (nome de usuário, celular).
4. **Autorização. Feita** (23/11/2026): perfis por empresa, permissão conferida em toda rota, sessão
   presa a uma empresa, isolamento entre empresas, administrador da plataforma, e as regras de cargo
   garantidas pelo banco com log próprio (ver "Autorização").
5. **Criptografia de dados pessoais. Feita** (antes do login, para o login já nascer com ela). Três
   camadas, descritas na seção abaixo.
6. **Cadastro de contas do banco. Feito**, no repositório de regras. Ver "Contas do banco" abaixo.

No diagrama, os quadros "Cadastros e logs", "Contas do banco", "Login" e "Autorização" mostram
tabelas existentes, conferidas campo a campo contra o Oracle, inclusive as dos termos (no quadro
"Login").

## Contas do banco: quem é, o que pode e validade da senha

Pedido: "cada usuário que for criado ir para uma tabela e lá a gente sabe qual usuário tem qual
liberação, qual cargo e como podemos monitorar o limite de 90 dias de senha, lembrando que isso
também se encaixa aos desenvolvedores". Há dois tipos de usuário, e cada um tem o seu painel:

| Quem | Onde mora | Painel | Estado |
|---|---|---|---|
| contas do **banco**: desenvolvedores, estagiários, infra, contas de API, o administrador | `SYS.KAWASHI_CONTA` (repositório de regras) | `SYS.V_KAWASHI_CONTAS` e `SYS.V_KAWASHI_CONTA_LIBERACOES`; no terminal, `infra/scripts/cadastro-de-contas.ps1` | **feito** |
| usuários da **aplicação** (quem entra na API) | `USUARIO`, `PESSOA`, `USUARIO_EMPRESA` | `V_USUARIO_ACESSOS`; pela API, `GET /api/usuarios` (administrador da empresa) | **feito** (23/11/2026) |

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
  "qual registro mudou"; nenhuma senha, token ou código fica em texto puro; `ACEITE_TERMO`
  registra o consentimento por versão.
