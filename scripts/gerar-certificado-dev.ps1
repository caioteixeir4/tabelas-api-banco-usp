<#
.SYNOPSIS
  Gera um certificado autoassinado (PKCS12) para rodar a API em HTTPS no desenvolvimento.

.DESCRIPTION
  A API so deve ser usada por HTTPS: um token ou um CPF que trafega em HTTP puro pode ser lido por
  qualquer um no caminho. Em producao o certificado vem de uma autoridade (ou o TLS termina num
  proxy reverso). Para desenvolver e testar localmente, este script cria um certificado autoassinado
  valido para "localhost" e grava as tres variaveis no .env:

      SSL_ATIVO=true            a API passa a atender em HTTPS
      SSL_KEYSTORE=...          caminho do arquivo .p12
      SSL_KEYSTORE_PASSWORD=... senha do arquivo (aleatoria, gerada aqui)
      EXIGIR_HTTPS=true         pedido em HTTP puro e recusado

  O arquivo .p12 e o .env ficam fora do git (ver .gitignore). O navegador e o curl vao avisar que o
  certificado nao e confiavel: e o esperado para um autoassinado (use "curl -k" nos testes).

.PARAMETER Destino
  Onde gravar o keystore. Padrao: certificado-dev.p12 na raiz do projeto.

.PARAMETER EnvFile
  O .env a atualizar. Padrao: .env na raiz do projeto.

.EXAMPLE
  .\scripts\gerar-certificado-dev.ps1
  mvn spring-boot:run          # a API sobe em https://localhost:8080
#>
param(
    [string]$Destino = (Join-Path $PSScriptRoot '..\certificado-dev.p12'),
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env')
)

$ErrorActionPreference = 'Stop'
$Destino = [System.IO.Path]::GetFullPath($Destino)
$EnvFile = [System.IO.Path]::GetFullPath($EnvFile)

if (Test-Path -LiteralPath $Destino) {
    throw "O arquivo '$Destino' ja existe. Apague-o antes se quiser gerar outro certificado."
}
if ((Test-Path -LiteralPath $EnvFile) -and (Select-String -LiteralPath $EnvFile -Pattern '^\s*SSL_KEYSTORE\s*=' -Quiet)) {
    throw "O arquivo '$EnvFile' ja tem SSL_KEYSTORE. Remova as linhas SSL_* e EXIGIR_HTTPS antes de gerar outro certificado."
}
$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool -and $env:JAVA_HOME) { $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe' }
if (-not $keytool -and (Get-Command java -ErrorAction SilentlyContinue)) {
    # O "java" do PATH no Windows costuma ser um atalho sem o keytool ao lado: pergunta ao proprio
    # Java onde o JDK esta instalado.
    $propriedades = cmd /c 'java -XshowSettings:properties -version 2>&1'
    $casa = ($propriedades | Select-String 'java\.home\s*=\s*(.+)$' | Select-Object -First 1).Matches.Groups[1].Value.Trim()
    if ($casa) { $keytool = Join-Path $casa 'bin\keytool.exe' }
}
if (-not $keytool -or -not (Test-Path -LiteralPath $keytool)) {
    throw "Nao achei o keytool (ele vem com o JDK). Coloque o JDK no PATH ou defina JAVA_HOME."
}

# Senha aleatoria so com letras e numeros (vai num arquivo .properties e numa linha de comando).
$alfabeto = (48..57) + (65..90) + (97..122)
$senha = -join (1..32 | ForEach-Object { [char]($alfabeto | Get-Random) })

# O keytool escreve o progresso na saida de erro; no Windows PowerShell isso viraria excecao com
# ErrorActionPreference = Stop, entao a chamada roda com "Continue" e o resultado e conferido depois.
$ErrorActionPreference = 'Continue'
& $keytool -genkeypair -alias api -keyalg RSA -keysize 3072 -validity 365 -storetype PKCS12 `
    -keystore $Destino -storepass $senha -dname 'CN=localhost, O=Kawashi Sistemas (desenvolvimento)' `
    -ext 'SAN=dns:localhost,ip:127.0.0.1' 2>&1 | Out-Null
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $Destino)) {
    throw "O keytool nao conseguiu gerar o certificado."
}

$linhas = @(
    '# HTTPS de desenvolvimento, gerado por scripts/gerar-certificado-dev.ps1',
    'SSL_ATIVO=true',
    "SSL_KEYSTORE=$($Destino -replace '\\', '/')",
    "SSL_KEYSTORE_PASSWORD=$senha",
    'EXIGIR_HTTPS=true'
)
$texto = ($linhas -join "`n") + "`n"
if (Test-Path -LiteralPath $EnvFile) {
    $atual = [System.IO.File]::ReadAllText($EnvFile)
    if ($atual.Length -gt 0 -and -not $atual.EndsWith("`n")) { $texto = "`n" + $texto }
}
[System.IO.File]::AppendAllText($EnvFile, $texto, [System.Text.UTF8Encoding]::new($false))

Write-Host "Certificado autoassinado gerado em $Destino (valido por 365 dias, para localhost)."
Write-Host "Variaveis SSL_* e EXIGIR_HTTPS acrescentadas a $EnvFile."
Write-Host "A API agora sobe em HTTPS. Nos testes manuais use: curl -k https://localhost:8080/actuator/health"
