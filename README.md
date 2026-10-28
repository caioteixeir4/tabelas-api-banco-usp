# tabelas-api-banco-usp

Tabelas de negócio e API do banco Oracle da Kawashi Sistemas. Projeto da pós-graduação na USP.

Este repositório roda sobre as regras de segurança do `regras-banco-usp` (hardening, ambientes,
perfis, auditoria, Vault). Aquele define quem entra no banco e como. Este define o que existe
dentro dos schemas. Os dois juntos orquestram o banco Oracle.

**Status: esqueleto.** O conteúdo veio do `regras-banco-usp` na separação entre regras e tabelas,
guardado byte a byte. Falta decidir como este repositório é aplicado sobre as regras, e a API
ainda não foi desenhada.

**Procedência:** tag `dominio-completo-pre-separacao` do repositório de regras (commit `cadb773`,
suíte de 250 testes verde). Qualquer arquivo daqui pode ser reconferido naquele repositório com
`git show dominio-completo-pre-separacao:<caminho>`.

## O que tem aqui

| Caminho | O que é |
|---|---|
| `oracle-init/11_exemplo_dominio_negocio.sql` | Tabelas padrão completas (`CLIENTE`, `ENDERECO`, `CONTATO`, `EMPRESA`, `FILIAL`, `SOCIO`), `GRANT REFERENCES` + `GRANT SELECT` pros schemas de serviço, e `SEFAZ.CONFIGURACAO_FISCAL`. Só em `TESTE_DEV` e `DEV`. |
| `oracle-init/12_exemplo_dominio_kawashi_core.sql` | Schemas `ORGANIZACOES`, `LOGIN`, `ADMINISTRACAO` (criação dos usuários/schemas + tabelas + grants cruzados), só em `TESTE_DEV` e `DEV`. |
| `tests/contrato/Test-InvarianteFkPadraoNuncaReferenciaServico.ps1` | O invariante original, com 5 casos (inclui os controles de `ADMINISTRACAO`/`LOGIN` e a conferência dos 6 schemas). No repositório de regras ele ficou com 2 casos. |
| `docs/arquitetura-dominio.md` | Seções 2.1 e 2.2 originais de `arquitetura.md`, com os diagramas do padrão e dos 3 schemas de plataforma. |

Os dois SQL e o teste são cópias **byte a byte** do que estava no repositório de regras (o teste
só mudou de pasta: era `infra/tests/caixa-branca/`, aqui está em `tests/contrato/`), de propósito,
pra rastrear a origem. `docs/arquitetura-dominio.md` é um **trecho** (seções 2.1 e 2.2) de
`infra/docs/arquitetura.md`, com um comentário HTML de procedência na primeira linha. O corpo
está igual ao original.

## O que este repositório pressupõe do `regras-banco-usp`

- **Os schemas já existem.** Os 6 ambientes (`KAWASHI_ENV_TESTE_DEV`, `_TESTE_INFRA`, `_DEV`,
  `_INFRA`, `_HOMOLOG`, `_PROD`), as tablespaces e os schemas de serviço `SEFAZ` e
  `SIMPLES_NACIONAL` são criados por `09_environments.sql` nas regras. O `12` daqui **cria** os
  schemas `ORGANIZACOES`/`LOGIN`/`ADMINISTRACAO` por conta própria, reaproveitando as tablespaces
  dos ambientes.
- **Ordem:** regras primeiro, tabelas depois. Os `11`/`12` assumem `ALTER SESSION SET CONTAINER =
  FREEPDB1` e rodam como `SYS`.
- **Fixture sobreposta:** o repositório de regras agora tem uma fixture mínima
  (`11_fixture_contrato_segmentacao.sql`) que cria `CLIENTE` e `SEFAZ.CONFIGURACAO_FISCAL` em
  `TESTE_DEV`/`DEV`. Aplicar o `11` completo daqui por cima falha (`ORA-00955`, tabela já
  existe). Quem aplicar precisa decidir se a fixture some das regras ou se o `11` daqui passa a
  criar só o que falta.

## Contrato que este repositório precisa cumprir

1. **FK só no sentido serviço → padrão, nunca o contrário.** O teste mora no repositório de
   regras (`Test-InvarianteFkPadraoNuncaReferenciaServico.ps1`) e é genérico, vale pra qualquer
   serviço novo. A versão de 5 casos daqui é a referência pra recriar os controles de
   `ADMINISTRACAO`/`LOGIN` como controles próprios.
2. **Serviço lê todas as tabelas padrão** (`GRANT SELECT` em loop no `11`). `REFERENCES` sozinho
   só habilita a FK, não a leitura (achado real, item 17 de `bugs-primeiro-teste.md` nas regras).
   Ponto em aberto: essa regra roda uma vez; tabelas padrão criadas depois não são alcançadas.
3. `ORGANIZACOES.EMPRESA` (tenants da plataforma) **não é** o `EMPRESA` do schema padrão (empresa
   de um cliente fiscal). `LOGIN.USUARIO` é o usuário do **site**, nunca uma conta de banco.

## Pendências conhecidas

- Testes de comportamento (INSERT/JOIN via proxy, unicidade de `CNPJ`, `USUARIO_EMPRESA` N:N)
  para `ORGANIZACOES`/`LOGIN`/`ADMINISTRACAO`. Hoje só a checagem estrutural do invariante os
  cobre. O modelo a seguir é `Test-SegmentacaoPorServico.ps1`, nas regras.
- Como este repositório é aplicado sobre as regras (script pós-bootstrap vs. submódulo): não
  decidido, precisa de teste ao vivo antes (o `oracle-init` roda uma vez, em pasta única).
- A API do banco: escopo e tecnologia ainda não definidos.
