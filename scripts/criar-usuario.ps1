<#
.SYNOPSIS
  Cria um usuario do login da API para uma pessoa ja cadastrada, com uma senha temporaria forte
  que vai para o Vault e nunca aparece na tela.

.DESCRIPTION
  Enquanto nao existe a autorizacao (etapa 4), nao ha endpoint para criar usuario: criar usuario
  sera coisa de administrador. Este script e o caminho, e o do primeiro usuario.

  O que ele faz:
    1. gera uma senha temporaria forte (gerador criptografico do sistema);
    2. guarda a senha no Vault, em kawashi-secrets/usuarios-api/<AMBIENTE>/<pessoa>, campo
       senha_temporaria (precisa do token do Vault do repositorio de regras);
    3. sobe a API SEM servidor web, so para gravar o usuario (CriarUsuarioNaSubida), e encerra.
       A senha vai para o processo por variavel de ambiente, nunca por argumento (ficaria no
       historico e na lista de processos);
    4. o usuario nasce com a senha VENCIDA: o primeiro login so serve para troca-la. A senha
       temporaria e conhecida por quem criou a conta; a definitiva, so pela pessoa.

  Entregue a senha temporaria a pessoa por um canal seguro, lendo do Vault:
      vault kv get -field=senha_temporaria kawashi-secrets/usuarios-api/<AMBIENTE>/<pessoa>
  e apague o segredo depois que ela trocar a senha.

  A pessoa precisa existir e estar ativa (cadastro de PESSOA na API). O e-mail e o nome de login.

.PARAMETER PessoaId
  Id da PESSOA que vai ter o usuario (relacao 1 para 1).

.PARAMETER Email
  E-mail de login.

.PARAMETER InfraEnvFile
  O .env do repositorio de regras, com o token do Vault. Padrao: ..\regras-banco-usp\infra\.env.

.EXAMPLE
  .\scripts\criar-usuario.ps1 -PessoaId 42 -Email caio@empresa.com.br
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [long]::MaxValue)]
    [long]$PessoaId,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[^@\s"''`]{1,64}@[^@\s"''`]+\.[^@\s"''`.]+$')]
    [string]$Email,

    [string]$InfraEnvFile
)

$ErrorActionPreference = 'Stop'
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
if (-not $token) { throw "Nao achei o token do Vault em '$InfraEnvFile'. A senha temporaria precisa ir para o Vault." }
$ambiente = if ($schema -match '^KAWASHI_ENV_(.+)$') { $Matches[1] } else { throw "Nao achei DB_SCHEMA no .env da API." }

# 1. Senha temporaria: 24 caracteres do gerador criptografico (sem caracteres que atrapalham em JSON/shell).
$alfabeto = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_.'
$bytes = New-Object byte[] 24
$gerador = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $gerador.GetBytes($bytes) } finally { $gerador.Dispose() }
$senha = -join ($bytes | ForEach-Object { $alfabeto[$_ % $alfabeto.Length] })

# 2. Vault primeiro: se nao der para guardar, nao cria um usuario cuja senha ninguem conhece.
$caminho = "kawashi-secrets/usuarios-api/$ambiente/$PessoaId"
$anterior = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
docker exec -e VAULT_TOKEN=$token kawashi-vault vault kv put $caminho "senha_temporaria=$senha" "email=$Email" 2>&1 | Out-Null
$okVault = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $anterior
if (-not $okVault) { throw "Nao consegui gravar no Vault ($caminho). Ele esta no ar? (vault-init.ps1 no repositorio de regras)" }

# 3. Cria pela propria API, sem servidor web, e encerra.
Write-Host "-> Criando o usuario (a API sobe sem servidor web e encerra)..."
$env:CRIAR_USUARIO_SENHA = $senha
try {
    Push-Location $raiz
    $argumentos = "--spring.main.web-application-type=none --app.criar-usuario.pessoa-id=$PessoaId --app.criar-usuario.email=$Email"
    $saida = (cmd /c "mvn -q -o spring-boot:run `"-Dspring-boot.run.arguments=$argumentos`" 2>&1") -join "`n"
    $codigo = $LASTEXITCODE
} finally {
    Remove-Item Env:\CRIAR_USUARIO_SENHA -ErrorAction SilentlyContinue
    Pop-Location
    $senha = $null
}

$criado = [regex]::Match($saida, 'USUARIO_CRIADO id=(\d+)')
if (-not $criado.Success) {
    $motivo = [regex]::Match($saida, 'USUARIO_NAO_CRIADO: ([^\r\n]+)').Groups[1].Value
    docker exec -e VAULT_TOKEN=$token kawashi-vault vault kv metadata delete $caminho 2>&1 | Out-Null
    throw "O usuario NAO foi criado (codigo $codigo). $motivo"
}
Write-Host ""
Write-Host "== Usuario criado (id $($criado.Groups[1].Value)) ==" -ForegroundColor Green
Write-Host "Senha temporaria no Vault: $caminho (campo senha_temporaria). Ela nao aparece aqui."
Write-Host "No primeiro login a pessoa so consegue trocar a senha. Depois disso, apague o segredo do Vault."
