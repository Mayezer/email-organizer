# AGENTS.md · Email Organizer

Este guia vale para todo o repositório `email_organizer`. O projeto importa mensagens do Gmail, analisa seu conteúdo e disponibiliza os resultados por uma API. A interface de oportunidades vive no repositório irmão `email-organizer-app`.

## Como trabalhar neste projeto

- Para trabalhos de interface que envolvam este repositório, consulte o andamento e as decisões no [PLANO_FRONTEND.md do App](../email-organizer-app/docs/PLANO_FRONTEND.md).
- Comunique-se em português do Brasil. Em tarefas de aprendizado, indique o arquivo, explique a responsabilidade da mudança e revise o raciocínio do usuário em passos pequenos. Forneça código de implementação ou altere os fontes quando o pedido autorizar esse trabalho.
- Preserve mudanças locais do usuário. Em caso de divergência com o README histórico ou o diagrama de produto, use o código, a configuração e as migrações atuais como referência para descrever o comportamento existente.
- Use dados sintéticos em testes e exemplos. Preserve a privacidade de mensagens e links. Consulte os arquivos de exemplo para nomes de configuração e mantenha valores reais de `.env`, credenciais e tokens fora de logs, diffs e documentação.

## Onde trabalhar

| Local | Responsabilidade |
| --- | --- |
| [Main.java](src/main/java/com/mayezer/emailorganizer/Main.java) | Ponto de entrada definido no Gradle. Monta as dependências e escolhe entre importação, relatório e preparação de nova tentativa. O build declara compatibilidade Java 17 e inclui Spring Boot. A execução atual ainda usa a montagem manual em `Main`. |
| [config/](src/main/java/com/mayezer/emailorganizer/config) | OAuth do Gmail, cliente OpenAI e conexão JDBC. |
| [service/](src/main/java/com/mayezer/emailorganizer/service) | `EmailService` pagina e importa, `EmailBodyExtractor` prepara texto, `EmailAnalysisService` solicita a análise, `EmailProcessingService` grava o resultado e `EmailReportService` o apresenta. |
| [EmailRepository.java](src/main/java/com/mayezer/emailorganizer/repository/EmailRepository.java) | SQL parametrizado e transições de processamento na tabela `emails`. |
| [api/emails/](api/emails), [api/authentication/](api/authentication) e [api/app/](api/app) | Modelo, migrações, serialização, rotas, JWT, permissões e configuração Django REST Framework. |
| [docker-compose.yml](docker-compose.yml) | Sobe Django e PostgreSQL. O importador Java roda no host e compartilha o banco com a API. |

## Contrato entre importador, banco e API

- A importação percorre as páginas do Gmail sem filtro explícito de caixa, remetente ou data. Usa `gmail_id` para reconhecer registros já importados e grava remetente, assunto, `thread_id` e `received_at` via JDBC. A data de recebimento vem de `internalDate`.
- Preserve a extração recursiva de texto simples, HTML e AMP HTML, a exclusão de anexos da análise e a conversão de links em texto sem acessá-los. Trate o corpo de e-mails e a saída da IA como dados externos. Mantenha o corpo bruto fora da persistência.
- O processamento lê registros `PENDING` e grava a análise como `COMPLETED`, com `processed_at`, ou marca a falha tratada como `FAILED`. A opção `--retry` devolve um registro `FAILED` para `PENDING` e a próxima execução normal o processa. O relatório seleciona análises concluídas nos últimos sete dias por `processed_at`.
- O Django gerencia a tabela `emails` em [models.py](api/emails/models.py) e na [migração inicial](api/emails/migrations/0001_initial.py). Preserve `gmail_id` como chave primária, os campos opcionais e os defaults no banco de `processing_status = PENDING` e `imported_at = Now()`, usados pelo JDBC. Evolua a tabela com migrações incrementais compatíveis com registros existentes.
- A análise usa trechos limitados de mensagens anteriores e grava `security_status` e `security_reason` separadamente de `category`. Campos de segurança nulos representam análises legadas ou ainda indisponíveis. A categoria usa igualdade exata no Django. Preserve `professional opportunities and recruitment` para a visão de oportunidades ou coordene a mudança do prompt, dos dados persistidos e do consumidor.
- Preserve o escopo Gmail somente leitura. A exclusão via Django afeta o registro local e uma nova importação pode recuperar a mensagem.

| Rota | Contrato atual |
| --- | --- |
| `/api/v1/emails/` | `GET` lista registros, com filtro exato opcional `?category=…`, e `POST` cria. A lista é um array sem paginação. |
| `/api/v1/emails/<gmail_id>/` | `GET` consulta e `DELETE` remove o registro local. |
| `/api/v1/authentication/token/`, `/api/v1/authentication/token/refresh/`, `/api/v1/authentication/token/verify` | Obtenção, renovação e verificação de JWT por `POST`. A rota `verify` termina sem barra. |

As operações de e-mail exigem JWT e a permissão Django correspondente (`emails.view_emails`, `emails.add_emails` ou `emails.delete_emails`). O conjunto de registros é compartilhado, sem escopo por usuário. Revise o consumidor no repositório irmão ao alterar forma da lista, nomes de campos, categoria, nulabilidade ou autenticação.

## Execução e validação local

- Use o Gradle Wrapper do repositório com um JDK compatível. `./gradlew test` executa a suíte JUnit. `EmailBodyExtractorTest` usa mensagens em memória e cobre formatos, anexos, partes aninhadas e corpo ausente.
- Use [`.env.example`](.env.example) para configurar `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` e `DJANGO_SECRET_KEY` no Compose. `docker compose --env-file .env.example config --quiet` valida a configuração sem usar o arquivo privado. Em um ambiente de desenvolvimento configurado, `docker compose up --build -d` aplica as migrações na inicialização da API e sobe os serviços. O Django usa `email_organizer_db:5432`. O host usa `127.0.0.1:5433` para o banco e a porta `8000` para a API.
- Para validar o Django com o serviço local disponível, execute `docker compose exec -T email_organizer_api python3 manage.py check`. Para mudanças no modelo, use também `python3 manage.py makemigrations --check --dry-run` no ambiente da API e confira o efeito da migração sobre registros existentes. `manage.py test` deve usar um banco de testes isolado. O arquivo atual de testes Django é um esqueleto.
- O Java lê o ambiente do processo. Para executar o fluxo completo, forneça as variáveis `POSTGRES_*`, `OPENAI_API_KEY` e o arquivo OAuth indicado por `GOOGLE_CREDENTIALS_PATH` (padrão `credentials.json`). O callback usa a porta `8888` e os tokens ficam em `tokens/`. `./gradlew run` consulta o Gmail e envia texto à OpenAI. Use esse modo apenas em teste de integração autorizado com a conta e o banco apropriados.
- Para exercitar os modos locais com um banco de teste, use `./gradlew run --args='--report'` ou `./gradlew run --args='--retry <gmail_id>'`. Os dois modos usam apenas o banco. O modo de nova tentativa altera o registro.
- Após aplicar a [migração de contexto e segurança](api/emails/migrations/0002_email_context_and_security.py), use `./gradlew run --args='--backfill-metadata 50'` para completar os metadados antigos pelo Gmail. Use o cursor indicado na saída para continuar o lote. Depois do preenchimento, `./gradlew run --args='--reanalyze 20'` reavalia análises legadas em lotes e consulta o Gmail e a OpenAI. Se uma reanálise falhar, a análise anterior continua disponível.

## Direção de produto

O App consulta a listagem geral e tem uma rota separada para oportunidades por categoria. O diagrama descreve incrementos futuros, como filtros na entrada do Gmail, acompanhamento de entrevistas e etapas, separação de convites, triagem para revisão e exclusão manual pelo usuário e agendamento semanal. O relatório disponível hoje é sob demanda no terminal. Mantenha a decisão sobre mensagens suspeitas com o usuário.
