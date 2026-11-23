<#
.SYNOPSIS
  Roteiro de fumaca por HTTP real contra a API em execucao.

.DESCRIPTION
  Os testes de integracao rodam dentro de uma transacao que e desfeita, e isso esconde erros que so
  aparecem em producao (como LazyInitializationException fora da transacao). Este roteiro chama a
  API de verdade: cria empresa, filial, pessoas de cada tipo, contatos e enderecos, viola as
  regras de proposito e confere o status de cada resposta. Trabalha DENTRO da empresa da sessao da
  conta de teste (a autorizacao prende cada sessao a uma empresa) e, se a conta for administradora da
  plataforma, cria empresas com a marca da rodada. No fim apaga os cadastros que criou e inativa as
  empresas criadas (empresa com perfis de acesso nao se apaga).

  A API precisa estar no ar (mvn spring-boot:run). Sai com codigo 1 se alguma verificacao falhar.

  O roteiro fala com a API como se fosse o BFF: todo pedido leva o segredo compartilhado
  (BFF_SEGREDO, lido do .env da API e nunca mostrado) e um IP de navegador. Confere tambem que, sem
  o segredo, a API recusa tudo (403).

  Toda a /api exige login, e o login e so por passkey. O roteiro usa uma CONTA DE TESTE com uma
  passkey de SOFTWARE (scripts/passkey-de-software.ps1), guardada no Vault em
  kawashi-secrets/smoke-passkey/<AMBIENTE>/<usuario>:
    - na primeira vez, crie a conta com criar-usuario.ps1 (de uma pessoa so para testes) e rode o
      roteiro com -UsuarioId: ele le o convite do Vault, cadastra a passkey de software, guarda a
      chave no Vault e apaga o convite;
    - nas vezes seguintes, entra com essa passkey.
  Passkey de software so serve para conta de teste do desenvolvimento: a chave privada fica no
  Vault, e nao presa num aparelho. No fim, faz logout e confere que o token parou de valer.

.PARAMETER Base
  URL base da API. Padrao: http://localhost:8080

.PARAMETER UsuarioId
  Id do usuario da conta de teste (o que o criar-usuario.ps1 mostrou).

.PARAMETER InfraEnvFile
  O .env do repositorio de regras, com o token do Vault. Padrao: ..\regras-banco-usp\infra\.env.

.EXAMPLE
  $env:PORT = 18080; mvn spring-boot:run      # em outro terminal
  .\scripts\criar-usuario.ps1 -PessoaId 42 -Email smoke@teste.kawashi.invalid   # so na primeira vez
  .\scripts\smoke-http.ps1 -Base http://localhost:18080 -UsuarioId 7
#>
param(
    [string]$Base = 'http://localhost:8080',
    [Parameter(Mandatory = $true)][ValidateRange(1, [long]::MaxValue)][long]$UsuarioId,
    [string]$InfraEnvFile
)

$ErrorActionPreference = 'Stop'
$aqui = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $aqui 'passkey-de-software.ps1')
if (-not $InfraEnvFile) { $InfraEnvFile = Join-Path $aqui '..\..\regras-banco-usp\infra\.env' }

function Get-ValorDoEnv {
    param([string]$Arquivo, [string]$Chave)
    if (-not (Test-Path -LiteralPath $Arquivo)) { return $null }
    foreach ($linha in [System.IO.File]::ReadAllLines($Arquivo)) {
        $p = $linha.Trim().Split('=', 2)
        if ($p.Count -eq 2 -and $p[0].Trim() -eq $Chave) { return $p[1].Trim() }
    }
    return $null
}
$envDaApi = Join-Path $aqui '..\.env'
$script:segredoDoBff = Get-ValorDoEnv $envDaApi 'BFF_SEGREDO'
$tokenDoVault = Get-ValorDoEnv $InfraEnvFile 'VAULT_DEV_ROOT_TOKEN_ID'
$schema = Get-ValorDoEnv $envDaApi 'DB_SCHEMA'
if (-not $script:segredoDoBff) { throw "Falta BFF_SEGREDO no .env da API (scripts/gerar-segredo-do-bff.ps1)." }
if (-not $tokenDoVault) { throw "Nao achei o token do Vault em '$InfraEnvFile' (a passkey da conta de teste fica la)." }
$ambiente = if ($schema -match '^KAWASHI_ENV_(.+)$') { $Matches[1] } else { throw 'Nao achei DB_SCHEMA no .env da API.' }
$caminhoDaPasskey = "kawashi-secrets/smoke-passkey/$ambiente/$UsuarioId"
$caminhoDoConvite = "kawashi-secrets/convites/$ambiente/$UsuarioId"

# Le um segredo do Vault (objeto com os campos) ou $null se nao existe.
function Read-Vault([string]$caminho) {
    $anterior = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $env:VAULT_TOKEN = $tokenDoVault
        $json = (docker exec -e VAULT_TOKEN kawashi-vault vault kv get -format=json $caminho 2>$null) -join "`n"
        if ($LASTEXITCODE -ne 0 -or -not $json) { return $null }
        return ($json | ConvertFrom-Json).data.data
    } finally { Remove-Item Env:\VAULT_TOKEN -ErrorAction SilentlyContinue; $ErrorActionPreference = $anterior }
}

# Guarda a passkey de software no Vault. Valores por variavel de ambiente (nunca na linha de comando);
# todos sao base64url ou numero, sem espaco nem curinga do sh.
function Save-Passkey($pk) {
    $anterior = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $env:VAULT_TOKEN = $tokenDoVault
        $env:KW_D = $pk.d; $env:KW_X = $pk.x; $env:KW_Y = $pk.y; $env:KW_ID = $pk.credencialId; $env:KW_H = $pk.handle; $env:KW_C = "$($pk.contador)"
        docker exec -e VAULT_TOKEN -e KW_D -e KW_X -e KW_Y -e KW_ID -e KW_H -e KW_C kawashi-vault sh -c ('vault kv put ' + $caminhoDaPasskey +
            ' d=$KW_D x=$KW_X y=$KW_Y credencialId=$KW_ID handle=$KW_H contador=$KW_C') 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Nao consegui guardar a passkey de teste no Vault ($caminhoDaPasskey)." }
    } finally {
        Remove-Item Env:\VAULT_TOKEN, Env:\KW_D, Env:\KW_X, Env:\KW_Y, Env:\KW_ID, Env:\KW_H, Env:\KW_C -ErrorAction SilentlyContinue
        $ErrorActionPreference = $anterior
    }
}

$base = $Base.TrimEnd('/')
Add-Type -AssemblyName System.Net.Http
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(30)
$script:ok = 0
$script:falhas = New-Object System.Collections.Generic.List[string]

function Chamar([string]$metodo, [string]$caminho, [string]$corpo = $null, [byte[]]$bytes = $null, [switch]$SemBff) {
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($metodo), "$base$caminho")
    if (-not $SemBff) {
        # Como o BFF: o segredo compartilhado e o IP do "navegador".
        [void]$req.Headers.TryAddWithoutValidation('X-Kawashi-Bff', $script:segredoDoBff)
        [void]$req.Headers.TryAddWithoutValidation('X-Kawashi-Ip-Cliente', '127.0.0.1')
    }
    if ($null -ne $bytes -and $bytes.Length -gt 0) {
        $req.Content = [System.Net.Http.ByteArrayContent]::new($bytes)
    } elseif (-not [string]::IsNullOrEmpty($corpo)) {
        $req.Content = [System.Net.Http.ByteArrayContent]::new([System.Text.Encoding]::UTF8.GetBytes($corpo))
    }
    if ($script:token) { $req.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $script:token) }
    if ($null -ne $req.Content) {
        $req.Content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/json; charset=utf-8')
    }
    $resp = $http.SendAsync($req).GetAwaiter().GetResult()
    $cru = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $texto = [System.Text.Encoding]::UTF8.GetString($cru)
    $json = $null
    if ($texto.Length -gt 0) { try { $json = $texto | ConvertFrom-Json } catch { $json = $null } }
    $loc = $null
    if ($null -ne $resp.Headers.Location) { $loc = $resp.Headers.Location.ToString() }
    [pscustomobject]@{ Status = [int]$resp.StatusCode; Json = $json; Texto = $texto; Location = $loc; Tipo = "$($resp.Content.Headers.ContentType)" }
}

function Confere([string]$nome, [bool]$condicao, [string]$detalhe = '') {
    if ($condicao) { $script:ok++; Write-Host "  ok    $nome" }
    else { $script:falhas.Add($nome); Write-Host "  FALHA $nome  $detalhe" }
}

function Digito([string]$digitos, [int[]]$pesos) {
    $soma = 0
    for ($i = 0; $i -lt $pesos.Count; $i++) { $soma += [int]$digitos.Substring($i, 1) * $pesos[$i] }
    $r = $soma % 11
    if ($r -lt 2) { return 0 } else { return (11 - $r) }
}
function NovaRaiz { -join (1..8 | ForEach-Object { Get-Random -Maximum 10 }) }
function NovoCnpj([string]$raiz, [string]$ordem) {
    $b = "$raiz$ordem"
    $d1 = Digito $b @(5,4,3,2,9,8,7,6,5,4,3,2)
    $d2 = Digito "$b$d1" @(6,5,4,3,2,9,8,7,6,5,4,3,2)
    return "$b$d1$d2"
}
function NovoCpf {
    do { $b = -join (1..9 | ForEach-Object { Get-Random -Maximum 10 }) } while ($b -match '^(\d)\1{8}$')
    $d1 = Digito $b @(10,9,8,7,6,5,4,3,2)
    $d2 = Digito "$b$d1" @(11,10,9,8,7,6,5,4,3,2)
    return "$b$d1$d2"
}
function Corpo($o) { $o | ConvertTo-Json -Compress }

$aTil = [string][char]0xE3
$marca = 'SMOKE' + (Get-Random -Maximum 1000000)
Write-Host "marca desta rodada: $marca"
$endereco = @{ logradouro = 'Rua das Flores'; cidade = "S$aTil" + 'o Paulo'; uf = 'sp'; cep = '01001-000' }

try {
    Write-Host '== saude'
    $r = Chamar GET '/actuator/health'
    Confere 'health UP' ($r.Status -eq 200 -and $r.Json.status -eq 'UP')

    Write-Host '== so o BFF e login por passkey'
    $script:token = $null
    $r = Chamar POST '/api/auth/passkey/login/opcoes' -SemBff
    Confere 'sem o segredo do BFF a API recusa ate o login (403)' ($r.Status -eq 403) "status=$($r.Status)"
    $r = Chamar GET '/api/empresas'
    Confere 'sem token a API recusa (401)' ($r.Status -eq 401)

    $pk = Read-Vault $caminhoDaPasskey
    if (-not $pk) {
        # Primeira vez: aceita o convite com uma passkey de software.
        $convite = Read-Vault $caminhoDoConvite
        if (-not $convite -or $convite.link -notmatch '#(kwc_[A-Za-z0-9_-]{43})$') {
            throw "Nao ha passkey de teste nem convite no Vault para o usuario $UsuarioId. Crie a conta com scripts/criar-usuario.ps1 (ou um convite novo com -UsuarioId $UsuarioId)."
        }
        $segredoDoConvite = $Matches[1]
        $r = Chamar POST '/api/auth/convite/opcoes' (Corpo @{ convite = $segredoDoConvite })
        Confere 'opcoes do convite 200' ($r.Status -eq 200) "status=$($r.Status) $($r.Texto)"
        $pk = New-PasskeyDeSoftware
        $credencial = Get-RespostaDeCadastro $pk $r.Json.publicKey.challenge $r.Json.publicKey.user.id
        $r = Chamar POST '/api/auth/convite' ('{"convite":"' + $segredoDoConvite + '","nome":"Smoke (software)","credencial":' + $credencial + '}')
        Confere 'convite aceito: token e 10 codigos de recuperacao' ($r.Status -eq 200 -and @($r.Json.codigosDeRecuperacao).Count -eq 10) "status=$($r.Status) $($r.Texto)"
        if ($r.Status -ne 200) { throw 'O convite nao foi aceito; sem login nao da para seguir.' }
        Save-Passkey $pk
        $anterior = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        $env:VAULT_TOKEN = $tokenDoVault
        docker exec -e VAULT_TOKEN kawashi-vault vault kv metadata delete $caminhoDoConvite 2>&1 | Out-Null
        Remove-Item Env:\VAULT_TOKEN; $ErrorActionPreference = $anterior
        $script:token = $r.Json.token
        $segredoDoConvite = $null
    }
    # Entra com a passkey (tambem na primeira vez, para provar que ela funciona).
    $r = Chamar POST '/api/auth/passkey/login/opcoes'
    Confere 'desafio de login 200' ($r.Status -eq 200 -and $r.Json.publicKey.challenge) "status=$($r.Status)"
    $resposta = Get-RespostaDeLogin $pk $r.Json.publicKey.challenge
    # O contador novo vai para o Vault ANTES do login: se o login falhar, o guardado fica a frente, e
    # o proximo nao parece um clone (contador voltando).
    Save-Passkey $pk
    $tokenDoConvite = $script:token
    $script:token = $null
    $r = Chamar POST '/api/auth/passkey/login' ('{"credencial":' + $resposta + '}')
    Confere 'login por passkey 200 com token kw_, escopo COMPLETO' ($r.Status -eq 200 -and $r.Json.token -match '^kw_' -and $r.Json.escopo -eq 'COMPLETO') "status=$($r.Status) $($r.Texto)"
    $x = Chamar POST '/api/auth/passkey/login' ('{"credencial":' + $resposta + '}')
    Confere 'a mesma resposta de novo e recusada (desafio de uso unico, 401)' ($x.Status -eq 401) "status=$($x.Status)"
    $script:token = $r.Json.token
    if ($tokenDoConvite) {
        $script:token = $tokenDoConvite; [void](Chamar POST '/api/auth/logout'); $script:token = $r.Json.token
    }

    Write-Host '== empresa da sessao (a conta de teste trabalha so nela)'
    $eu = Chamar GET '/api/auth/eu'
    Confere 'a sessao ja nasce presa a empresa (a conta tem acesso a uma so)' ($eu.Status -eq 200 -and $eu.Json.empresaId) "status=$($eu.Status) $($eu.Texto)"
    if (-not $eu.Json.empresaId) { throw 'A conta de teste precisa de acesso a uma empresa (criar-usuario.ps1 -UsuarioId ... modo vincular, ou rode o ServidorRealTest uma vez).' }
    $empresaId = $eu.Json.empresaId
    $emp = (Chamar GET "/api/empresas/$empresaId").Json
    $raiz = $emp.cnpj.Substring(0, 8)
    $r = Chamar GET '/api/empresas'
    Confere 'a lista de empresas mostra so a da sessao' ($r.Status -eq 200 -and $r.Json.total -eq 1 -and $r.Json.itens[0].id -eq $empresaId)

    Write-Host '== plataforma (empresa nova com o primeiro administrador)'
    $novaEmpresa = { param($cnpj, $razao) Corpo @{ cnpj = $cnpj; razaoSocial = $razao; administrador = @{ cpf = (NovoCpf); nome = 'Admin Smoke'; email = "adm$(Get-Random)@teste.kawashi.invalid" } } }
    if ($eu.Json.adminPlataforma) {
        $cnpjNova = NovoCnpj (NovaRaiz) '0001'
        $r = Chamar POST '/api/plataforma/empresas' (& $novaEmpresa $cnpjNova "  $marca Ltda  ")
        Confere 'plataforma cria empresa (201) e o convite do administrador vem uma vez' ($r.Status -eq 201 -and $r.Json.convite -match '^kwc_') "status=$($r.Status) $($r.Texto)"
        $nova = $r.Json.empresaId
        $r = Chamar GET "/api/plataforma/empresas?razaoSocial=$marca"
        Confere 'razaoSocial aparada' ($r.Status -eq 200 -and @($r.Json.itens | Where-Object { $_.razaoSocial -eq "$marca Ltda" }).Count -eq 1)
        $r = Chamar GET "/api/empresas/$nova"
        Confere 'a plataforma nao enxerga os dados da empresa que criou (404 na sessao de outra empresa)' ($r.Status -eq 404) "status=$($r.Status)"
        $r = Chamar POST '/api/plataforma/empresas' (& $novaEmpresa $cnpjNova "$marca outra")
        Confere 'CNPJ duplicado 409' ($r.Status -eq 409)
        $r = Chamar POST '/api/plataforma/empresas' (& $novaEmpresa (NovoCnpj (NovaRaiz) '0002') "$marca filial")
        Confere 'empresa que nao e matriz 422' ($r.Status -eq 422)
        $r = Chamar POST '/api/plataforma/empresas' (& $novaEmpresa (NovoCnpj (NovaRaiz) '0001') ($marca + ($aTil * (200 - $marca.Length))))
        Confere '200 caracteres acentuados cabem' ($r.Status -eq 201) "status=$($r.Status)"
        $r = Chamar POST "/api/plataforma/empresas/$nova/inativar"
        Confere 'plataforma inativa empresa 200' ($r.Status -eq 200 -and $r.Json.ativa -eq $false)
        $r = Chamar DELETE "/api/plataforma/empresas/$nova"
        Confere 'empresa com perfis de acesso nao se exclui (409): se inativa' ($r.Status -eq 409)
    } else {
        $r = Chamar POST '/api/plataforma/empresas' (& $novaEmpresa (NovoCnpj (NovaRaiz) '0001') "$marca Ltda")
        Confere 'sem ser administrador da plataforma, criar empresa da 403' ($r.Status -eq 403)
    }

    Write-Host '== filial'
    $ordem = '{0:D4}' -f (Get-Random -Minimum 100 -Maximum 9999)
    $r = Chamar POST "/api/empresas/$empresaId/filiais" (Corpo @{ cnpj = (NovoCnpj $raiz $ordem); nome = "Filial $marca" })
    Confere 'POST filial 201' ($r.Status -eq 201) "status=$($r.Status) $($r.Texto)"
    $filialId = $r.Json.id
    $c = Chamar GET "/api/filiais/$filialId"
    Confere 'criadoEm do GET igual ao do POST' ($c.Status -eq 200 -and $c.Json.criadoEm -eq $r.Json.criadoEm)
    $r = Chamar POST "/api/empresas/$empresaId/filiais" (Corpo @{ cnpj = (NovoCnpj (NovaRaiz) '0002'); nome = 'Raiz diferente' })
    Confere 'filial com raiz diferente 422' ($r.Status -eq 422)
    Write-Host '== pessoa (o tipo e a separacao)'
    $pes = "/api/empresas/$empresaId/pessoas"
    $cpfCliente = NovoCpf
    $r = Chamar POST $pes (Corpo @{ tipo = 'cliente'; natureza = 'f'; documento = $cpfCliente; nome = 'Cliente Um' })
    Confere 'POST pessoa CLIENTE 201 (tipo e natureza normalizados)' ($r.Status -eq 201 -and $r.Json.tipo -eq 'CLIENTE' -and $r.Json.natureza -eq 'F') "status=$($r.Status) $($r.Texto)"
    $mascaraCpf = '***.' + $cpfCliente.Substring(3, 3) + '.' + $cpfCliente.Substring(6, 3) + '-**'
    Confere 'documento sai MASCARADO na resposta (o CPF inteiro nao aparece)' ($r.Json.documento -eq $mascaraCpf -and $r.Texto -notmatch $cpfCliente) "documento=$($r.Json.documento)"
    $idCliente = $r.Json.id
    $rv = Chamar GET "/api/pessoas/$idCliente/documento"
    Confere 'revelar devolve o documento inteiro (GET /api/pessoas/{id}/documento)' ($rv.Status -eq 200 -and $rv.Json.documento -eq $cpfCliente) "status=$($rv.Status)"
    $rv = Chamar PUT "/api/pessoas/$idCliente" (Corpo @{ tipo = 'CLIENTE'; natureza = 'F'; documento = $mascaraCpf; nome = 'Cliente Um' })
    Confere 'atualizar devolvendo a mascara vale por "mesmo documento" (200)' ($rv.Status -eq 200) "status=$($rv.Status) $($rv.Texto)"
    $clienteId = $r.Json.id
    $r = Chamar POST $pes (Corpo @{ tipo = 'FORNECEDOR'; natureza = 'J'; documento = (NovoCnpj (NovaRaiz) '0001'); nome = 'Fornecedor Ltda' })
    Confere 'fornecedor pessoa juridica 201' ($r.Status -eq 201)
    $r = Chamar POST $pes (Corpo @{ tipo = 'COLABORADOR'; natureza = 'F'; documento = $cpfCliente; nome = 'Cliente Um'; cargo = 'Analista' })
    Confere 'mesmo CPF com outro tipo e outra linha (201)' ($r.Status -eq 201)
    $r = Chamar POST $pes (Corpo @{ tipo = 'CLIENTE'; natureza = 'F'; documento = $cpfCliente; nome = 'Repetido' })
    Confere 'mesmo CPF e mesmo tipo 409' ($r.Status -eq 409)
    $r = Chamar POST $pes (Corpo @{ tipo = 'CLIENTE'; natureza = 'F'; documento = (NovoCnpj (NovaRaiz) '0001'); nome = 'Doc errado' })
    Confere 'CNPJ com natureza F 400 no campo documento' ($r.Status -eq 400 -and $r.Json.erros[0].campo -eq 'documento')
    $r = Chamar POST $pes (Corpo @{ tipo = 'SOCIO'; natureza = 'F'; documento = (NovoCpf); nome = 'Socio A'; participacao = 60 })
    Confere 'socio 60% 201' ($r.Status -eq 201)
    $r = Chamar POST $pes (Corpo @{ tipo = 'SOCIO'; natureza = 'F'; documento = (NovoCpf); nome = 'Estoura'; participacao = 50 })
    Confere 'soma dos socios acima de 100% 422' ($r.Status -eq 422)
    $r = Chamar POST $pes (Corpo @{ tipo = 'SOCIO'; natureza = 'F'; documento = (NovoCpf); nome = 'Sem participacao' })
    Confere 'socio sem participacao 422' ($r.Status -eq 422)
    $r = Chamar POST $pes (Corpo @{ tipo = 'CLIENTE'; natureza = 'F'; documento = (NovoCpf); nome = 'Com cargo'; cargo = 'Chefe' })
    Confere 'cargo em quem nao e colaborador 422' ($r.Status -eq 422)
    $r = Chamar GET "$pes`?tipo=SOCIO"
    Confere 'filtro por tipo devolve so os socios' ($r.Status -eq 200 -and $r.Json.total -eq 1)

    Write-Host '== contato e endereco (de filial e de pessoa)'
    foreach ($dono in @("/api/filiais/$filialId", "/api/pessoas/$clienteId")) {
        $r = Chamar POST "$dono/contatos" (Corpo @{ tipo = 'EMAIL'; valor = 'FULANO@Exemplo.com.br' })
        Confere "$dono contato e-mail 201, mascarado e principal" ($r.Status -eq 201 -and $r.Json.valor -eq 'f***@exemplo.com.br' -and $r.Json.principal -eq $true) "status=$($r.Status) $($r.Texto)"
        $r = Chamar POST "$dono/contatos" (Corpo @{ tipo = 'TELEFONE'; valor = '(11) 98765-4321' })
        Confere "$dono contato telefone 201, mascarado" ($r.Status -eq 201 -and $r.Json.valor -eq '(11) *****-4321')
        $rv = Chamar GET "/api/contatos/$($r.Json.id)/valor"
        Confere "$dono revelar telefone devolve so digitos" ($rv.Status -eq 200 -and $rv.Json.valor -eq '11987654321') "status=$($rv.Status)"
        $r = Chamar POST "$dono/contatos" (Corpo @{ tipo = 'EMAIL'; valor = 'sem-arroba' })
        Confere "$dono e-mail invalido 400" ($r.Status -eq 400)
        $r = Chamar POST "$dono/enderecos" (Corpo $endereco)
        Confere "$dono endereco 201, UF maiuscula, CEP so digitos, principal" ($r.Status -eq 201 -and $r.Json.uf -eq 'SP' -and $r.Json.cep -eq '01001000' -and $r.Json.principal -eq $true) "status=$($r.Status) $($r.Texto)"
        $r = Chamar GET "$dono/contatos"
        Confere "$dono lista 2 contatos" ($r.Status -eq 200 -and $r.Json.total -eq 2)
    }

    Write-Host '== limites, conflitos e erros'
    $r = Chamar GET '/api/empresas?page=2147483647'
    Confere 'page gigante 400 (nao 500)' ($r.Status -eq 400)
    $r = Chamar GET '/api/empresas?sort=naoExiste,asc'
    Confere 'sort por campo inexistente 400' ($r.Status -eq 400)
    $r = Chamar GET "/api/empresas?razaoSocial=$([uri]::EscapeDataString('%'))&size=1"
    Confere 'filtro "%" nao vira curinga' ($r.Status -eq 200 -and $r.Json.total -eq 0)
    $r = Chamar POST '/api/empresas' -bytes (New-Object byte[] 70000)
    Confere 'corpo de 70000 bytes 413 problem+json' ($r.Status -eq 413 -and $r.Tipo -like 'application/problem+json*')
    $r = Chamar GET '/api/pessoas/999999999'
    Confere 'pessoa inexistente 404 problem+json' ($r.Status -eq 404 -and $r.Tipo -like 'application/problem+json*')
    $r = Chamar DELETE "/api/pessoas/$clienteId"
    Confere 'excluir pessoa com contato e endereco 409' ($r.Status -eq 409)
    $r = Chamar GET '/api/perfis'
    Confere 'o administrador ve os perfis da empresa (ADMINISTRADOR, OPERADOR, CONSULTA)' ($r.Status -eq 200 -and @($r.Json | Where-Object { $_.nome -in 'ADMINISTRADOR', 'OPERADOR', 'CONSULTA' }).Count -eq 3)
    $r = Chamar GET '/api/usuarios/historico'
    Confere 'o historico de cargos da empresa responde (LOG_PERMISSAO)' ($r.Status -eq 200)
}
finally {
    Write-Host '== limpeza (o que esta rodada criou)'
    # Na empresa da sessao: filiais com a marca e as pessoas criadas agora (com contatos e enderecos).
    $donos = @()
    if ($empresaId) {
        foreach ($f in @((Chamar GET "/api/empresas/$empresaId/filiais?size=100").Json.itens | Where-Object { $_.nome -eq "Filial $marca" })) { $donos += "/api/filiais/$($f.id)" }
        foreach ($p in @((Chamar GET "/api/empresas/$empresaId/pessoas?size=100").Json.itens | Where-Object { $_.nome -in 'Cliente Um', 'Fornecedor Ltda', 'Socio A' })) { $donos += "/api/pessoas/$($p.id)" }
    }
    foreach ($d in $donos) {
        foreach ($tipo in @('contatos', 'enderecos')) {
            $itens = @((Chamar GET "$d/$tipo`?size=100").Json.itens)
            # o principal so sai por ultimo: primeiro os demais
            foreach ($i in ($itens | Where-Object { -not $_.principal })) { [void](Chamar DELETE "/api/$tipo/$($i.id)") }
            foreach ($i in ($itens | Where-Object { $_.principal })) { [void](Chamar DELETE "/api/$tipo/$($i.id)") }
        }
        [void](Chamar DELETE $d)
    }
    $sobra = 0
    if ($empresaId) {
        $sobra += @((Chamar GET "/api/empresas/$empresaId/filiais?size=100").Json.itens | Where-Object { $_.nome -eq "Filial $marca" }).Count
        $sobra += @((Chamar GET "/api/empresas/$empresaId/pessoas?size=100").Json.itens | Where-Object { $_.nome -in 'Cliente Um', 'Fornecedor Ltda', 'Socio A' }).Count
    }
    Confere "limpeza: $($donos.Count) cadastro(s) apagado(s), nada sobrou na empresa da sessao" ($sobra -eq 0) "sobraram=$sobra"
    # Empresas da plataforma nao se apagam (tem perfis de acesso): ficam inativas.
    $criadas = @((Chamar GET "/api/plataforma/empresas?razaoSocial=$marca&size=100").Json.itens)
    foreach ($e in ($criadas | Where-Object { $_.ativa })) { [void](Chamar POST "/api/plataforma/empresas/$($e.id)/inativar") }
    $ativas = @((Chamar GET "/api/plataforma/empresas?razaoSocial=$marca&size=100").Json.itens | Where-Object { $_.ativa }).Count
    Confere "as $($criadas.Count) empresa(s) criada(s) pela plataforma ficaram inativas" ($ativas -eq 0) "ativas=$ativas"    if ($script:token) {
        $x = Chamar POST '/api/auth/logout'
        $y = Chamar GET '/api/auth/eu'
        Confere 'logout 204 e o token para de valer na hora (401)' ($x.Status -eq 204 -and $y.Status -eq 401) "logout=$($x.Status) depois=$($y.Status)"
    }
}

Write-Host ''
Write-Host "RESULTADO: $($script:ok) ok, $($script:falhas.Count) falha(s)"
Write-Host '(as linhas de LOG desta rodada ficam no banco: a API nao tem como apaga-las, e esse e o proposito)'
if ($script:falhas.Count -gt 0) { $script:falhas | ForEach-Object { Write-Host "  - $_" }; exit 1 }
