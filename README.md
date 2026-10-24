# sugestao-busca

Camada de sugestão de busca parametrizada por perfil e comportamento, sobre Solr.
Implementa o desenho de solução *Sugestão de busca no app*.

Este repositório é o ambiente de desenvolvimento local do POC. Nada aqui usa dado
real: o vocabulário é sintético e os perfis são falsos, em memória.

## O que já está instalado nesta máquina

Tudo fora do `Program Files`, em pastas do usuário, sem privilégio de administrador.

| | versão | onde |
|---|---|---|
| Temurin JDK | 25.0.4 LTS | `C:\Users\migue\tools\jdk-25` |
| Apache Solr | 10.0.0 (com módulo `ltr`) | `C:\Users\migue\tools\solr-10.0.0` |
| PortableGit | 2.56.0 | `C:\Users\migue\tools\PortableGit` |
| Maven | via `mvnw`, baixado na primeira build | — |

O JDK 17 continua sendo o padrão do sistema. O Solr 10 exige Java 21+, então os
scripts apontam o `SOLR_JAVA_HOME` para o JDK 25 apenas no processo que sobem.

## Subir o ambiente

### Uma vez só: política de execução

O padrão do Windows é `Restricted` e nenhum `.ps1` deste repositório roda. Não há
política de grupo por cima, então isto resolve de vez — terminal normal, sem admin:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

`RemoteSigned` deixa rodar script local e continua exigindo assinatura em script
baixado. Se preferir não mudar a política, dá para rodar cada um avulso:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\solr-local.ps1 start
```

### Solr

Hoje, do zip — não há runtime de container nesta máquina:

```powershell
.\scripts\solr-local.ps1 start
.\scripts\criar-colecao.ps1
```

Painel em <http://localhost:8983/solr>. Para derrubar: `.\scripts\solr-local.ps1 stop`.

### A aplicação

```powershell
$env:JAVA_HOME = 'C:\Users\migue\tools\jdk-25'
.\mvnw spring-boot:run
```

Saúde em <http://localhost:8080/actuator/health>.

## Solr em Docker

O `compose.yaml` está pronto e aponta para `solr:10.0.0` com o módulo `ltr`, na
mesma porta e com a mesma coleção. Ele não roda ainda: **não há Docker, Podman nem
WSL nesta máquina**, e habilitar qualquer um deles exige elevação, que não dá para
fazer a partir daqui.

Para habilitar, em um PowerShell **aberto como Administrador**:

```powershell
.\scripts\habilitar-docker.ps1
```

O script habilita a **Virtual Machine Platform** e o **WSL 2** — é exatamente o que
o instalador do Docker Desktop reclama faltar. O usuário desta máquina é
administrador local, então o UAC pede só confirmação, sem senha.

Se o terminal elevado ainda estiver com a política `Restricted`, não vale a pena
contornar por causa de um comando. O script inteiro se resume a isto, e dá para
digitar direto:

```powershell
wsl --install --no-distribution
```

Depois: reiniciar, rodar de novo o instalador do Docker Desktop (**x86_64**, não o
Arm), e então, de volta a um terminal normal:

```powershell
docker compose up -d
.\scripts\criar-colecao.ps1
```

O `criar-colecao.ps1` é o mesmo nos dois caminhos — ele fala com `localhost:8983`
e não sabe se do outro lado está o zip ou o container. Trocar de um para o outro
não muda nada no projeto.

## Estrutura

```
compose.yaml                      Solr 10 em container (quando houver runtime)
scripts/solr-local.ps1            Solr 10 do zip, sem container e sem admin
scripts/criar-colecao.ps1         cria a coleção e aplica o schema
solr/schema-sugestoes.json        payload da Schema API
solr/README.md                    por que cada campo existe
src/main/resources/application.properties
                                  prazos, cotas e o piso de k-anonimato
```

## Decisões que já estão no código

- **Não existe starter do Spring para Solr** desde o Boot 2.4. O SolrJ 10 entra como
  dependência comum e o client vira um `@Bean` nosso.
- **Duas análises de prefixo**, uma para a frase e outra por palavra, com pesos
  diferentes na consulta. O porquê está em `solr/README.md`.
- **Sinônimo é campo do documento**, não arquivo de configuração: o negócio edita a
  redação alternativa junto do termo, sem reload.
- **`clientes_distintos_i` entra como `fq`**, nunca como pós-filtro. É o piso de
  k-anonimato, e é o único parâmetro do projeto que, afrouxado, vira incidente de
  privacidade em vez de queda de relevância.
- **O módulo `ltr` sobe junto, mas não é usado.** As camadas 1 a 3 do desenho
  entregam sem modelo nenhum; a 4 espera impressão registrada com posição.

## Próximos passos

1. Vocabulário sintético: 2 a 5 mil termos bancários com contadores plausíveis.
2. `GET /v1/sugestoes` com o leque paralelo em threads virtuais.
3. Página de demonstração: digitar as mesmas letras com perfis diferentes.
