<#
.SYNOPSIS
  Cria um usuario do login para uma pessoa ja cadastrada (ou um convite novo para quem perdeu o
  acesso). Nao ha senha: o usuario recebe um LINK DE CONVITE, que vai para o Vault e nunca aparece
  na tela.

.DESCRIPTION
  O login e so por passkey (ver docs/api-e-regras.md, "Login"). A conta nasce com um convite de
  uso unico, valido por CONVITE_HORAS (24 h): no link, a pessoa cadastra a passkey do aparelho dela
  e recebe os codigos de recuperacao. Enquanto nao existe a autorizacao (etapa 4), nao ha endpoint
  para criar usuario; este script e o caminho.

  O que ele faz:
    1. confere que o Vault esta no ar (o link precisa ir para algum lugar);
    2. sobe a API SEM servidor web, so para criar o usuario/convite (CriarUsuarioNaSubida), e
       encerra. O segredo do convite vem por um arquivo temporario, nunca pela saida nem por log;
    3. monta o link  <Site>/convite.html#kwc_...  (o segredo vai depois do #: o navegador nunca
       o envia ao servidor) e guarda no Vault, em kawashi-secrets/convites/<AMBIENTE>/<usuario>,
       campo link. Apaga o arquivo temporario.

  Entregue o link a pessoa por um canal seguro, lendo do Vault:
      vault kv get -field=link kawashi-secrets/convites/<AMBIENTE>/<usuario>
  e apague o segredo depois que ela cadastrar a passkey (o convite so vale uma vez, de qualquer jeito).

  Modos:
    -PessoaId e -Email   usuario novo (a pessoa precisa existir e estar ativa);
    -UsuarioId           convite novo para um usuario que ja existe e perdeu as passkeys e os
                         codigos. Ao ser usado, derruba todas as sessoes dele.

.PARAMETER PessoaId
  Id da PESSOA que vai ter o usuario (relacao 1 para 1).

.PARAMETER Email
  E-mail da conta (aparece no aparelho como nome da passkey).

.PARAMETER UsuarioId
  Id do usuario que precisa de um convite novo.

.PARAMETER Site
  Endereco do site (do BFF). Padrao: http://localhost:9090.

.PARAMETER InfraEnvFile
  O .env do repositorio de regras, com o token do Vault. Padrao: ..\regras-banco-usp\infra\.env.

.EXAMPLE
  .\scripts\criar-usuario.ps1 -PessoaId 42 -Email caio@empresa.com.br

.EXAMPLE
  .\scripts\criar-usuario.ps1 -UsuarioId 7
#>
[CmdletBinding(DefaultParameterSetName = 'Novo')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Novo')]
    [ValidateRange(1, [long]::MaxValue)]
    [long]$PessoaId,

    [Parameter(Mandatory = $true, ParameterSetName = 'Novo')]
    [ValidatePattern('^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$')]
    [string]$Email,

    [Parameter(Mandatory = $true, ParameterSetName = 'Convite')]
    [ValidateRange(1, [long]::MaxValue)]
    [long]$UsuarioId,

    [ValidatePattern('^https?://[A-Za-z0-9.-]+(:\d+)?$')]
    [string]$Site = 'http://localhost:9090',

    [string]$InfraEnvFile
)

$ErrorActionPreference = 'Stop'
# Pasta calculada aqui: com [CmdletBinding()] e -File, o $PSScriptRoot fica vazio no bloco param.
$aqui = Split-Path -Parent $MyInvocation.MyCommand.Path
$raiz = (Resolve-Path (Join-Path $aqui '..')).Path
if (-not $InfraEnvFile) { $InfraEnvFile = Join-Path $raiz '..\regras-banco-usp\infra\.env' }

function Get-ValorDoEnv {
    param([string]$Arquivo, [string]$Chave)
    if (-not (Test-Path -LiteralPath $Arquivo)) { return $null }
    foreach ($linha in [System.IO.File]::ReadAllLines($Arquivo)) {
        $p = $linha.Trim().Split('=', 2)
        if ($p.Count -eq 2 -and $p[0].Trim() -eq $Chave) { return $p[1].Trim() }
    }
    return $null
}

$token = Get-ValorDoEnv $InfraEnvFile 'VAULT_DEV_ROOT_TOKEN_ID'
$schema = Get-ValorDoEnv (Join-Path $raiz '.env') 'DB_SCHEMA'
if (-not $token) { throw "Nao achei o token do Vault em '$InfraEnvFile'. O link do convite precisa ir para o Vault." }
$ambiente = if ($schema -match '^KAWASHI_ENV_(.+)$') { $Matches[1] } else { throw "Nao achei DB_SCHEMA no .env da API." }

# 1. Vault no ar e com o engine de segredos (o modo dev perde tudo ao reiniciar: vault-init.ps1).
$anterior = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$env:VAULT_TOKEN = $token
docker exec -e VAULT_TOKEN kawashi-vault vault secrets list 2>&1 | Select-String -SimpleMatch 'kawashi-secrets/' -Quiet | Set-Variable okVault
Remove-Item Env:\VAULT_TOKEN
$ErrorActionPreference = $anterior
if (-not $okVault) { throw "O Vault nao esta no ar ou nao tem o engine kawashi-secrets (rode infra/scripts/vault-init.ps1 no repositorio de regras)." }

# 2. Cria pela propria API, sem servidor web, e encerra. O segredo volta por arquivo temporario.
$arquivo = Join-Path ([IO.Path]::GetTempPath()) ("kw-convite-" + [guid]::NewGuid().ToString('N') + ".tmp")
$argumentos = if ($PSCmdlet.ParameterSetName -eq 'Novo') {
    "--spring.main.web-application-type=none --app.criar-usuario.modo=novo --app.criar-usuario.pessoa-id=$PessoaId --app.criar-usuario.email=$Email"
} else {
    "--spring.main.web-application-type=none --app.criar-usuario.modo=convite --app.criar-usuario.usuario-id=$UsuarioId"
}
Write-Host "-> Criando o convite (a API sobe sem servidor web e encerra)..."
$env:CRIAR_USUARIO_ARQUIVO = $arquivo
try {
    Push-Location $raiz
    $saida = (cmd /c "mvn -q -o spring-boot:run `"-Dspring-boot.run.arguments=$argumentos`" 2>&1") -join "`n"
    $codigo = $LASTEXITCODE
} finally {
    Remove-Item Env:\CRIAR_USUARIO_ARQUIVO -ErrorAction SilentlyContinue
    Pop-Location
}

$criado = [regex]::Match($saida, 'USUARIO_CRIADO id=(\d+)')
if (-not $criado.Success -or -not (Test-Path -LiteralPath $arquivo)) {
    Remove-Item -LiteralPath $arquivo -ErrorAction SilentlyContinue
    $motivo = [regex]::Match($saida, 'USUARIO_NAO_CRIADO: ([^\r\n]+)').Groups[1].Value
    if (-not $motivo) { $motivo = 'Veja se a API compila e se o .env esta completo (inclusive BFF_SEGREDO).' }
    throw "O convite NAO foi criado (codigo $codigo). $motivo"
}
$id = $criado.Groups[1].Value

# 3. Link no Vault. Token e link vao por variavel de ambiente ("docker exec -e NOME" nao poe o valor
#    na linha de comando); o link so tem caracteres de URL, sem espaco nem curinga do sh.
try {
    $segredo = [IO.File]::ReadAllText($arquivo).Trim()
} finally {
    # Sobrescreve antes de apagar: o segredo nao fica no disco.
    [IO.File]::WriteAllBytes($arquivo, (New-Object byte[] 128))
    Remove-Item -LiteralPath $arquivo -Force
}
if ($segredo -notmatch '^kwc_[A-Za-z0-9_-]{43}$') { throw "O arquivo do convite veio num formato inesperado. Nada foi para o Vault." }
$caminho = "kawashi-secrets/convites/$ambiente/$id"
$anterior = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
try {
    $env:VAULT_TOKEN = $token
    $env:KW_LINK = "$Site/convite.html#$segredo"
    $env:KW_EXPIRA = (Get-Date).ToUniversalTime().AddHours(24).ToString('yyyy-MM-ddTHH:mmZ')
    docker exec -e VAULT_TOKEN -e KW_LINK -e KW_EXPIRA kawashi-vault sh -c ('vault kv put ' + $caminho + ' link=$KW_LINK expira_em=$KW_EXPIRA') 2>&1 | Out-Null
    $okVault = ($LASTEXITCODE -eq 0)
} finally {
    Remove-Item Env:\KW_LINK, Env:\KW_EXPIRA, Env:\VAULT_TOKEN -ErrorAction SilentlyContinue
    $ErrorActionPreference = $anterior
    $segredo = $null
}
if (-not $okVault) {
    throw "O convite foi criado (usuario $id), mas NAO consegui grava-lo no Vault. Ele vence sozinho; gere outro com -UsuarioId $id."
}

Write-Host ""
Write-Host "== Convite criado (usuario $id) ==" -ForegroundColor Green
Write-Host "Link no Vault: $caminho (campo link). Ele nao aparece aqui."
Write-Host "Vale uma vez, por 24 horas. Depois que a pessoa cadastrar a passkey, apague o segredo do Vault."
