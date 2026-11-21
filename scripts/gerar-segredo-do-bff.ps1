<#
.SYNOPSIS
  Gera o segredo que a API e o BFF compartilham (BFF_SEGREDO) e grava nos DOIS .env, sem mostrar.

.DESCRIPTION
  A API so atende o BFF (o servidor do site, repositorio bff-banco-usp): todo pedido precisa do
  cabecalho X-Kawashi-Bff com este segredo (ver FiltroDoBff.java). Sem ele, nem a API nem o BFF
  sobem.

  O script sorteia 48 bytes com o gerador criptografico do sistema (64 caracteres base64url) e
  grava a linha BFF_SEGREDO no .env da API e no .env do BFF. O valor NUNCA aparece na tela. Quando
  o .env do repositorio de regras esta ao lado (com o token do Vault), uma copia vai para o Vault em
  kawashi-secrets/bff/<AMBIENTE>.

  Se ja existe segredo, o script para; com -Trocar ele gera um novo e troca so a linha BFF_SEGREDO
  nos dois arquivos (as outras linhas ficam como estao). Depois de trocar, reinicie a API e o BFF.

.PARAMETER EnvFile
  O .env da API. Padrao: .env na raiz deste projeto.

.PARAMETER BffEnvFile
  O .env do BFF. Padrao: ..\bff-banco-usp\.env (criado se nao existir).

.PARAMETER InfraEnvFile
  O .env do repositorio de regras (tem VAULT_DEV_ROOT_TOKEN_ID). Sem ele, a copia no Vault e pulada.

.PARAMETER Trocar
  Troca um segredo que ja existe.

.EXAMPLE
  .\scripts\gerar-segredo-do-bff.ps1

.PARAMETER SoCopiarParaOVault
  Nao gera nada: so copia para o Vault o segredo que ja esta nos .env (o Vault de desenvolvimento e
  em memoria e perde tudo ao reiniciar).

.EXAMPLE
  .\scripts\gerar-segredo-do-bff.ps1 -Trocar

.EXAMPLE
  .\scripts\gerar-segredo-do-bff.ps1 -SoCopiarParaOVault
#>
param(
    [string]$EnvFile,
    [string]$BffEnvFile,
    [string]$InfraEnvFile,
    [switch]$Trocar,
    [switch]$SoCopiarParaOVault
)

$ErrorActionPreference = 'Stop'
# $PSScriptRoot fica vazio nos valores padrao de param() quando o script roda com -File: o caminho sai daqui.
$aqui = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $EnvFile) { $EnvFile = Join-Path $aqui '..\.env' }
if (-not $BffEnvFile) { $BffEnvFile = Join-Path $aqui '..\..\bff-banco-usp\.env' }
if (-not $InfraEnvFile) { $InfraEnvFile = Join-Path $aqui '..\..\regras-banco-usp\infra\.env' }
# Caminho relativo vale a partir da pasta atual do PowerShell (e nao da pasta do processo).
$EnvFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($EnvFile)
$BffEnvFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($BffEnvFile)
$InfraEnvFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($InfraEnvFile)

$CHAVE = 'BFF_SEGREDO'
$utf8 = [System.Text.UTF8Encoding]::new($false)

function Get-ValorDoEnv {
    param([string]$Arquivo, [string]$Chave)
    if (-not (Test-Path -LiteralPath $Arquivo)) { return $null }
    foreach ($linha in [System.IO.File]::ReadAllLines($Arquivo)) {
        $partes = $linha.Trim().Split('=', 2)
        if ($partes.Count -eq 2 -and $partes[0].Trim() -eq $Chave) { return $partes[1].Trim() }
    }
    return $null
}

# Grava a linha da chave: troca a que existe ou acrescenta no fim. As demais linhas nao mudam.
function Set-LinhaDoEnv {
    param([string]$Arquivo, [string]$Chave, [string]$Valor, [string]$Comentario)
    $linhas = New-Object System.Collections.Generic.List[string]
    $trocou = $false
    if (Test-Path -LiteralPath $Arquivo) {
        foreach ($linha in [System.IO.File]::ReadAllLines($Arquivo)) {
            $partes = $linha.Trim().Split('=', 2)
            if ($partes.Count -eq 2 -and $partes[0].Trim() -eq $Chave) {
                if (-not $trocou) { $linhas.Add("$Chave=$Valor"); $trocou = $true }
            } else {
                $linhas.Add($linha)
            }
        }
    }
    if (-not $trocou) {
        $linhas.Add($Comentario)
        $linhas.Add("$Chave=$Valor")
    }
    [System.IO.File]::WriteAllText($Arquivo, ($linhas -join "`n") + "`n", $utf8)
}

if (-not (Test-Path -LiteralPath (Split-Path -Parent $BffEnvFile))) {
    throw "Nao achei a pasta do BFF ('$(Split-Path -Parent $BffEnvFile)'). Informe -BffEnvFile."
}

$existeApi = [bool](Get-ValorDoEnv $EnvFile $CHAVE)
$existeBff = [bool](Get-ValorDoEnv $BffEnvFile $CHAVE)
if ($SoCopiarParaOVault) {
    $segredo = Get-ValorDoEnv $EnvFile $CHAVE
    if (-not $segredo -or $segredo -ne (Get-ValorDoEnv $BffEnvFile $CHAVE)) {
        throw "Para copiar, $CHAVE precisa existir e ser IGUAL nos dois .env. Rode sem -SoCopiarParaOVault (ou com -Trocar)."
    }
} else {
    if (($existeApi -or $existeBff) -and -not $Trocar) {
        throw "Ja existe $CHAVE (API: $existeApi, BFF: $existeBff). Para gerar outro, rode com -Trocar."
    }

    # 48 bytes do gerador criptografico do sistema, em base64url (64 caracteres, sem '=', '+' ou '/').
    $bytes = New-Object byte[] 48
    $gerador = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $gerador.GetBytes($bytes) } finally { $gerador.Dispose() }
    $segredo = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')

    $comentario = "# Segredo compartilhado entre a API e o BFF (gerar-segredo-do-bff.ps1). O MESMO valor nos dois .env."
    Set-LinhaDoEnv $EnvFile $CHAVE $segredo $comentario
    Set-LinhaDoEnv $BffEnvFile $CHAVE $segredo $comentario
    Write-Host "$CHAVE gravado nos dois .env (API e BFF). O valor nao aparece aqui." -ForegroundColor Green
    if ($Trocar) { Write-Host "Reinicie a API e o BFF para valer o segredo novo." -ForegroundColor Yellow }
}

# Copia no Vault, quando o token esta disponivel. O token e o segredo vao por variavel de ambiente:
# "docker exec -e NOME" (sem "=valor") repassa a variavel sem o valor aparecer na linha de comando
# (lista de processos). Pela entrada padrao nao da: o PowerShell 5.1 poe um BOM na frente do JSON.
$token = Get-ValorDoEnv $InfraEnvFile 'VAULT_DEV_ROOT_TOKEN_ID'
$schema = Get-ValorDoEnv $EnvFile 'DB_SCHEMA'
$ambiente = if ($schema -match '^KAWASHI_ENV_(.+)$') { $Matches[1] } else { $null }
if (-not $token -or -not $ambiente) {
    Write-Host "AVISO: copia no Vault pulada (sem o token do Vault ou sem DB_SCHEMA no .env da API)." -ForegroundColor Yellow
    exit 0
}
$anterior = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $env:VAULT_TOKEN = $token
    $env:KW_SEGREDO = $segredo
    # Sem aspas duplas no comando (o PowerShell 5.1 as estraga em argumento de programa externo). O
    # segredo e base64url: sem espaco nem curinga, nao precisa delas no sh.
    docker exec -e VAULT_TOKEN -e KW_SEGREDO kawashi-vault sh -c ('vault kv put kawashi-secrets/bff/' + $ambiente + ' segredo=$KW_SEGREDO') 2>&1 | Out-Null
    $okVault = ($LASTEXITCODE -eq 0)
} finally {
    Remove-Item Env:\KW_SEGREDO, Env:\VAULT_TOKEN -ErrorAction SilentlyContinue
    $ErrorActionPreference = $anterior
    $segredo = $null
}
if ($okVault) {
    Write-Host "Copia guardada no Vault (kawashi-secrets/bff/$ambiente)." -ForegroundColor Green
} else {
    Write-Host "AVISO: nao consegui gravar a copia no Vault (ele esta no ar?). O segredo esta nos dois .env." -ForegroundColor Yellow
}
