# Importação em segundo plano

O Django cria o trabalho no PostgreSQL e responde sem consultar o Gmail. O importador Java reserva uma página de até cinco mensagens por vez e confirma os metadados junto com o cursor da página seguinte. A listagem pode ler essas mensagens assim que a transação confirma. Depois da última página, o importador analisa uma mensagem por reserva. Uma falha de análise marca a mensagem como `FAILED` e mantém os metadados disponíveis.

Configure uma única conta por instalação. Defina `EMAIL_SYNC_ACCOUNT` com o endereço do Gmail nos ambientes do Django e do Java. Aplique as migrações antes de iniciar o importador. Se ainda não houver autorização OAuth salva, execute `./gradlew run --args='--authorize-gmail'` para conceder acesso somente leitura. Em seguida, inicie `./gradlew run --args='--sync-worker'` com as variáveis `POSTGRES_*` e `OPENAI_API_KEY`. O worker verifica a conta do token salvo e funciona sem abrir uma janela de autorização.

Use o JWT de serviço para chamar:

| Operação | Rota | Permissão Django |
| --- | --- | --- |
| Iniciar ou acompanhar a importação ativa | `POST /api/v1/email-sync-jobs/` | `emails.add_emailsyncjob` e `emails.view_emailsyncjob` |
| Consultar situação e contadores | `GET /api/v1/email-sync-jobs/<id>/` | `emails.view_emailsyncjob` |

O `POST` devolve `202` e reutiliza o mesmo ID enquanto houver um trabalho ativo para a conta. Os estados são `IMPORTING`, `ANALYZING`, `COMPLETED` e `FAILED`. `analysis_failed_count` conta falhas de mensagens individuais, que podem ocorrer mesmo quando o trabalho termina como `COMPLETED`. Depois de `--retry <gmail_id>`, um novo trabalho de sincronização processa as mensagens pendentes dessa conta. Registros anteriores ao fluxo em segundo plano continuam no modo de importação do terminal.

Ao interromper o worker, inicie-o novamente com a mesma configuração. Uma reserva abandonada expira em até três minutos. A próxima execução retoma da última página e da posição dentro dela que foram confirmadas. Se o Gmail não responder, inclusive com HTTP `429`, a etapa mantém o cursor e tenta novamente após 5, 10, 20 e 40 segundos; a quinta falha seguida encerra o trabalho. Uma nova solicitação parte do último histórico confirmado da conta, e o `gmail_id` impede novas cópias.

## Sincronização incremental

Aplique também a migração `0011_email_sync_history` antes de iniciar o worker atualizado. O checkpoint fica em `email_sync_checkpoints`, por conta, e é independente dos cursores de navegação e de mudanças do App. Os IDs do Gmail são guardados como texto, sem conversão para inteiros de 64 bits.

Na primeira carga, o worker captura o `historyId` antes de começar a varredura. Cada etapa importa no máximo cinco metadados e confirma o cursor na mesma transação. Após a última página, consulta as mensagens adicionadas desde o ponto inicial para incluir o que chegou durante a carga. Os trabalhos seguintes usam `users.history.list` com `messageAdded`, sem repetir a listagem completa. O histórico só avança quando todas as páginas retornadas foram consumidas. Registros de histórico com mais de cinco mensagens são divididos em etapas, com a posição interna persistida junto dos metadados.

HTTP `404` na consulta do histórico indica expiração: o worker descarta o checkpoint e começa outra carga completa em lotes, preservando os registros já importados. Se o histórico expirar novamente no mesmo trabalho, ele termina como `FAILED` para limitar as tentativas; uma nova solicitação pode reiniciar a recuperação. Um `404` ou `410` ao buscar uma mensagem individual apenas ignora aquela mensagem indisponível. São preservados o escopo OAuth somente leitura e a exclusão de Spam e Lixeira da importação. Alterações de marcadores e exclusões no Gmail não removem registros locais nesta etapa.

Essas transições seguem o [contrato de sincronização do Gmail](https://developers.google.com/workspace/gmail/api/guides/sync) e a [referência de histórico](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.history/list).

## Progresso no App

Aplique também as migrações `0012_email_processing_updates` e `0013_email_update_revision_index`. Elas mantêm `import_revision` como referência de navegação e acrescentam `update_revision` para publicar mudanças de processamento e classificação no mesmo contador confirmado de importações. Os registros antigos permanecem disponíveis e entram no fluxo de atualizações quando forem alterados. O índice é criado sem bloquear as escritas durante sua construção.

O Spring expõe `POST /api/email-sync-jobs` e `GET /api/email-sync-jobs/<id>`, usando as permissões do serviço Django descritas acima. **Atualizar** inicia ou acompanha o trabalho ativo, consulta o estado a cada dois segundos e lê as mudanças confirmadas com `GET /api/email-changes?cursor=…&include_updates=true`. Sem esse parâmetro, a rota mantém o modo que lista apenas importações. A resposta de metadados inclui `processing_status` e `effective_category`, que prioriza a classificação manual.

Cada resposta de mudanças traz até cinco mensagens. A tela aplica cada lote imediatamente, inclusive o restante de uma a quatro mensagens, e concilia os cartões pelo `gmail_id`. Análises concluídas ou com falha atualizam o cartão existente. O acompanhamento continua ao navegar pelas páginas ou categorias, e encerra ao sair da tela ou depois de consumir o último lote de um trabalho terminado. Uma falha preserva a lista e o último cursor recebido.

Há no máximo uma requisição de acompanhamento em andamento, dez lotes por ciclo, seis segundos por requisição e 150 ciclos em uma sessão de até cinco minutos, além do tempo necessário para encerrar uma requisição já iniciada. Ao atingir o limite, a tela permite continuar com **Atualizar**; o trabalho do servidor segue independente da aba. A leitura normal de páginas continua separada do acompanhamento.

Os testes Java de integração podem ser executados com `EMAIL_SYNC_TEST_DB_URL` apontando para um banco PostgreSQL descartável com as migrações aplicadas. Eles gravam apenas mensagens sintéticas e não chamam Gmail nem OpenAI.

## Acesso pelo App

1. No Django, defina `EMAIL_APP_SERVICE_USERNAME` com o mesmo usuário dedicado que o Spring usa em `EMAIL_ORGANIZER_API_USERNAME`. Conceda ao serviço `emails.view_emails`, `emails.change_emails`, `emails.add_emailsyncjob` e `emails.view_emailsyncjob`.
2. No Spring, defina `EMAIL_ORGANIZER_APP_GMAIL_ACCOUNT` com o mesmo endereço de `EMAIL_SYNC_ACCOUNT`, e `EMAIL_ORGANIZER_APP_USERNAME` com o usuário da pessoa. Gere o hash bcrypt da senha do App com `htpasswd -nBC 12 <usuário>` e configure só o trecho depois de `:` em `EMAIL_ORGANIZER_APP_PASSWORD_HASH`. Se carregar as variáveis por um shell, use aspas simples no hash para preservar os caracteres `$`.
3. Sirva o Angular e o proxy de `/api/` na mesma origem. Na publicação, use HTTPS e configure `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` no Spring. O cookie da sessão usa HTTP-only e expira após 30 minutos de inatividade. O navegador recebe o endereço vinculado depois do login. O JWT de serviço e o token Gmail permanecem nos servidores.

Com `EMAIL_APP_SERVICE_USERNAME` definido, as rotas de e-mail e sincronização do Django exigem o JWT desse serviço e o cabeçalho `X-Email-Account` correspondente a `EMAIL_SYNC_ACCOUNT`. O Spring acrescenta o cabeçalho a partir da configuração do servidor. Em instalações sem esse usuário configurado, as rotas Django preservam o acesso legado por JWT para clientes de infraestrutura, e qualquer solicitação do App com o cabeçalho de conta é recusada. Registros com `sync_account` nulo ficam preservados no banco e fora da conta exibida pelo App. A migração `0019_email_account_indexes` acrescenta índices para a paginação geral e as revisões por conta.
