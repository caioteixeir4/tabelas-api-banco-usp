<#
.SYNOPSIS
  Gera as duas chaves que a API usa para cifrar dados pessoais (CPF/CNPJ de pessoa, e-mail,
  telefone) antes de gravar no banco, e as guarda no .env local e, se possivel, no Vault.

.DESCRIPTION
  A API guarda esses dados cifrados (ver CifraDeDados.java e docs/modelo-de-dados.md, secao
  "Criptografia e LGPD"). Para isso precisa de duas chaves de 256 bits, em base64:

      CIFRA_CHAVE_DADOS    cifra e decifra o valor (AES-256-GCM)
      CIFRA_CHAVE_INDICE   gera a impressao digital usada para unicidade e busca (HMAC-SHA256)

  Este script sorteia as duas com o gerador criptografico do sistema e acrescenta ao .env, que
  fica fora do git. As chaves NUNCA aparecem na tela.

  ATENCAO: perder a CIFRA_CHAVE_DADOS e perder os dados cifrados, sem volta. Por isso, quando o
  .env do repositorio de regras esta ao lado (com o token do Vault), uma copia vai para o Vault em
  kawashi-secrets/cifra/<AMBIENTE>. O Vault de desenvolvimento e em memoria e perde tudo ao
  reiniciar: rode este script com -SoCopiarParaOVault depois de reiniciar o Vault para repor a
  copia. Em producao as chaves devem nascer e morar num cofre de verdade (Vault persistente, KMS).

  O script se recusa a trocar chaves que ja existem: trocar a chave com dados gravados os tornaria
  ilegiveis.

.PARAMETER EnvFile
  O .env da API a atualizar. Padrao: .env na raiz do projeto.

.PARAMETER InfraEnvFile
  O .env do repositorio de regras (tem VAULT_DEV_ROOT_TOKEN_ID). Padrao: ..\regras-banco-usp\infra\.env,
  se existir. Sem ele, a copia no Vault e pulada com um aviso.

.PARAMETER SoCopiarParaOVault
  Nao gera nada: so copia para o Vault as chaves que ja estao no .env.

.EXAMPLE
  .\scripts\gerar-chaves-de-cifra.ps1

.EXAMPLE
  .\scripts\gerar-chaves-de-cifra.ps1 -SoCopiarParaOVault
#>
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env'),
    [string]$InfraEnvFile = (Join-Path $PSScriptRoot '..\..\regras-banco-usp\infra\.env'),
    [switch]$SoCopiarParaOVault
)

$ErrorActionPreference = 'Stop'
# Caminho relativo vale a partir da pasta atual do PowerShell. ([IO.Path]::GetFullPath usaria a pasta
# do processo, que pode ser outra, e o script procuraria o .env no lugar errado.)
$EnvFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($EnvFile)
$InfraEnvFile = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($InfraEnvFile)

function Get-ValorDoEnv {
    param([string]$Arquivo, [string]$Chave)
    if (-not (Test-Path -LiteralPath $Arquivo)) { return $null }
    foreach ($linha in [System.IO.File]::ReadAllLines($Arquivo)) {
        $partes = $linha.Trim().Split('=', 2)
        if ($partes.Count -eq 2 -and $partes[0].Trim() -eq $Chave) { return $partes[1].Trim() }
    }
    return $null
}

function New-Chave {
    # Gerador criptografico do sistema (nao Get-Random, que nao serve para chave).
    $bytes = New-Object byte[] 32
    $gerador = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $gerador.GetBytes($bytes) } finally { $gerador.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

$dados  = Get-ValorDoEnv $EnvFile 'CIFRA_CHAVE_DADOS'
$indice = Get-ValorDoEnv $EnvFile 'CIFRA_CHAVE_INDICE'

if ($SoCopiarParaOVault) {
    if (-not $dados -or -not $indice) { throw "O arquivo '$EnvFile' nao tem as duas chaves (CIFRA_CHAVE_DADOS e CIFRA_CHAVE_INDICE). Rode sem -SoCopiarParaOVault para gerar." }
} else {
    if ($dados -or $indice) {
        throw "O arquivo '$EnvFile' ja tem chave de cifra. Este script nao troca chaves: trocar a chave deixaria ilegiveis os dados ja gravados."
    }
    $dados = New-Chave
    $indice = New-Chave
    $texto = "# Chaves de cifra dos dados pessoais (gerar-chaves-de-cifra.ps1). Perder a de dados = perder os dados.`nCIFRA_CHAVE_DADOS=$dados`nCIFRA_CHAVE_INDICE=$indice`n"
    if (Test-Path -LiteralPath $EnvFile) {
        $atual = [System.IO.File]::ReadAllText($EnvFile)
        if ($atual.Length -gt 0 -and -not $atual.EndsWith("`n")) { $texto = "`n" + $texto }
    }
    [System.IO.File]::AppendAllText($EnvFile, $texto, [System.Text.UTF8Encoding]::new($false))
    Write-Host "Chaves geradas e gravadas em $EnvFile (CIFRA_CHAVE_DADOS e CIFRA_CHAVE_INDICE)." -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# Copia no Vault, quando o token esta disponivel
# ---------------------------------------------------------------------------
$token = Get-ValorDoEnv $InfraEnvFile 'VAULT_DEV_ROOT_TOKEN_ID'
$schema = Get-ValorDoEnv $EnvFile 'DB_SCHEMA'
$ambiente = if ($schema -match '^KAWASHI_ENV_(.+)$') { $Matches[1] } else { $null }

if (-not $token -or -not $ambiente) {
    Write-Host "AVISO: copia no Vault pulada (nao achei o token do Vault em '$InfraEnvFile' ou o DB_SCHEMA no .env)." -ForegroundColor Yellow
    Write-Host "       Guarde uma copia das duas chaves num cofre: sem a CIFRA_CHAVE_DADOS os dados cifrados nao voltam." -ForegroundColor Yellow
    exit 0
}

$anterior = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
docker exec -e VAULT_TOKEN=$token kawashi-vault vault kv put "kawashi-secrets/cifra/$ambiente" "chave_dados=$dados" "chave_indice=$indice" 2>&1 | Out-Null
$okVault = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $anterior

if ($okVault) {
    Write-Host "Copia das chaves guardada no Vault (kawashi-secrets/cifra/$ambiente)." -ForegroundColor Green
} else {
    Write-Host "AVISO: nao consegui gravar a copia no Vault (ele esta no ar? rode vault-init.ps1 no repositorio de regras)." -ForegroundColor Yellow
    Write-Host "       As chaves estao no .env. Rode de novo com -SoCopiarParaOVault quando o Vault estiver de pe." -ForegroundColor Yellow
}
