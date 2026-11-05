# Email Organizer

A learning project that retrieves Gmail messages, prepares their content for AI analysis, and stores the results in PostgreSQL. A Python/Django API provides access to the stored records.

The learning focus is Java: basic logic, object-oriented programming, null handling, decision-making, API integration, database access, and testing. Spring Boot integration is the next development stage.

This documentation was prepared on **October 27, 2026**, using files permitted by .gitignore and the source code recorded in commit **c99c0c5**. It describes the behavior found in the code; external integrations were not executed or validated while writing it.

This README is self-contained and uses explanations and actual project names without implementation examples or ready-to-run commands.

## Table of contents

- [1. Purpose and features](#1-purpose-and-features)
- [2. Version status](#2-version-status)
- [3. Technologies](#3-technologies)
- [4. Architecture and structure](#4-architecture-and-structure)
- [5. Import workflow](#5-import-workflow)
- [6. Content extraction and analysis](#6-content-extraction-and-analysis)
- [7. Processing and retries](#7-processing-and-retries)
- [8. Report](#8-report)
- [9. Database](#9-database)
- [10. Configuration and environment setup](#10-configuration-and-environment-setup)
- [11. Django API](#11-django-api)
- [12. Docker and supporting files](#12-docker-and-supporting-files)
- [13. Tests](#13-tests)
- [14. Current limitations](#14-current-limitations)
- [15. Product vision and next steps](#15-product-vision-and-next-steps)
- [16. Study roadmap](#16-study-roadmap)

## 1. Purpose and features

Email Organizer helps users understand their messages, identify opportunities, and recognize when an email requires action.

| Feature | Implemented behavior |
| --- | --- |
| Gmail authorization | Uses OAuth to access the authorized account. |
| Paginated import | Iterates through the message pages returned by Gmail. |
| Duplicate detection | Checks the message identifier before inserting a record. |
| Basic registration | Stores the Gmail identifier, sender, and subject. |
| Body extraction | Handles plain text, HTML, and AMP HTML, including nested parts. |
| Attachment handling | Skips parts identified by a filename or attachment disposition. |
| HTML preparation | Converts content to text and includes link addresses. |
| AI analysis | Requests a category, a summary in Portuguese, and an indication of whether action is required. |
| Processing status | Tracks pending, completed, and failed messages. |
| Retry preparation | Returns a failed message to the pending state. |
| Report | Displays analyses completed within the last seven days in the terminal. |
| Authenticated API | Supports listing, creating, retrieving, and deleting records. |

The current Gmail integration requests read-only access. In this version, organizing means recording and analyzing messages. The application does not yet create folders or labels, move messages, or delete them from the account. The permission scope is described in the [official Gmail scopes documentation](https://developers.google.com/workspace/gmail/api/auth/scopes).

## 2. Version status

This documentation covers the workflow before full Spring Boot integration.

| Reference | Observed status |
| --- | --- |
| Entry point in the committed version | Main, which assembles dependencies and selects the execution mode. |
| Compatibility declared in the committed build | Java 11. This setting alone does not determine the JVM needed to run Gradle and every dependency. |
| Local work in progress | Initial Spring Boot setup, a change to declared Java 17 compatibility, and registration of the extractor as a component. |
| Local entry-point prototype | EmailOrganizerApplication.java, which receives the extractor and prints a message. This file is excluded from the documentation commit. |
| Publication scope | This README only. Implementation changes and personal study material remain outside the documentation commit. |

In the local working copy, the build may already point to the experimental Spring entry point. In that state, the default execution does not automatically run the Main workflow. Before running the application, identify which entry point is selected.

## 3. Technologies

The versions below are those declared in the project files at the time of review. They are not upgrade recommendations or confirmation of runtime compatibility.

| Technology | Recorded version or setting | Purpose |
| --- | --- | --- |
| Java | Compatibility 11 in the committed build; 17 in the local change | Import, analysis, and reporting application. |
| Gradle Wrapper | 9.8.1 | Build, dependency management, and test tasks. |
| Google API Client | 2.0.0 | Infrastructure for accessing Google APIs. |
| Google OAuth Client with Jetty | 1.34.1 | OAuth authorization and local callback handling. |
| Gmail API Java | v1-rev20220404-2.0.0 | Message retrieval. |
| PostgreSQL JDBC | 42.7.8 | Communication between the Java application and the database. |
| jsoup | 1.23.2 | HTML parsing and text extraction. |
| OpenAI Java SDK | 4.91.0 | Client for the analysis integration. |
| JUnit Jupiter | 5.14.4 | Java test authoring. |
| PostgreSQL in Docker | 18.6 | Record storage. |
| Python in the Docker image | 3.14.4 | API runtime. |
| Django | 6.1.2 | Python application framework. |
| Django REST Framework | 3.18.3 | HTTP operations on stored records. |
| Simple JWT | 5.5.1 | Token-based API authentication. |
| psycopg | 3.3.6 | PostgreSQL access from Django. |
| Spring Boot, in local changes | 4.1.2 | Next stage of Java dependency management. |

Gradle 9 requires a compatible JVM; Java 11 cannot run that Gradle release line. Check the [official compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html) when selecting a JDK. Source compatibility and the JVM used to run the build tool are separate concerns.

## 4. Architecture and structure

The application has two parts that share PostgreSQL:

1. The Java application retrieves Gmail messages, analyzes them, and stores the results.
2. The Django API reads and changes database records according to user authentication and permissions.

Java does not call the Django API during processing. The API does not trigger the Java importer either. Their current connection is the shared email table.

| Location | Responsibility |
| --- | --- |
| src/main/java/com/mayezer/emailorganizer | Java entry point and application packages. |
| config package | External client creation and database connection setup. |
| model package | Data representations used during processing. |
| repository package | JDBC database queries and updates. |
| service package | Import, extraction, analysis, processing, and reporting. |
| src/test/java/com/mayezer/emailorganizer/service | Extractor tests. |
| api/app | Django configuration, main routes, and infrastructure. |
| api/emails | Email model, serialization, permissions, routes, and operations. |
| api/authentication | Token obtain, refresh, and verification routes. |
| gradle/wrapper | Gradle Wrapper configuration and supporting files. |

In Java, services receive some dependencies through their constructors. Main creates and connects these objects. The extractor is still instantiated directly inside the import and processing services. This assembly process will be one focus of the Spring migration.

## 5. Import workflow

During a normal Main execution without additional options:

1. The application opens a PostgreSQL connection.
2. It creates the email repository.
3. It prepares the Gmail and OpenAI clients and the analysis service.
4. It requests a page of Gmail messages.
5. For each identifier, it checks whether the message is already in the database.
6. It skips messages that have already been registered.
7. It retrieves the details of new messages and reads the sender and subject.
8. It stores the basic data of each new message.
9. It continues while Gmail provides another page.
10. After importing messages, it starts processing pending records.

The current query does not define a custom filter for subject, date range, sender, or unread status, and does not explicitly restrict the search to the inbox label. It iterates through the results of the listing call used by the code.

The import service also extracts the body, but that extraction result is not used to store the content. The processing service retrieves the body from Gmail again later.

## 6. Content extraction and analysis

### Extraction

A message can contain multiple parts and different representations of the same content. The extractor traverses these parts, including parts nested inside other parts.

It recognizes three formats: plain text, HTML, and AMP HTML. Received content is decoded from the Base64 format used by Gmail and interpreted as UTF-8.

The extractor preserves the decoded content of each format. For analysis, it also produces text versions of HTML and AMP HTML. Link addresses are included alongside their labels; the extractor does not visit those links. The distinction between text, HTML, and attributes is explained in the [official jsoup guide](https://jsoup.org/cookbook/extracting-data/attributes-text-html).

Parts with a nonblank filename or an attachment disposition header are skipped. The final text combines nonblank formats and identifies the origin of each block. When the same information appears in several formats, it may be repeated in the material sent for analysis.

### AI analysis

The analysis service combines the sender, subject, and prepared text. Its instructions request one of these categories:

- Professional opportunities and recruitment.
- Event invitations.
- Social media updates.
- Advertising.
- Potential fraud.
- Other.

The expected result contains a category, summary, and indication of whether action is required. The summary is requested in Portuguese and should highlight relevant details such as the company, opportunity, dates, and deadlines. The model configured in the service at the time of review is gpt-6-luna.

The instructions also ask the model to treat commands found inside an email as content to analyze and to indicate manual review for suspicious messages. This describes the intended behavior; the generated response still needs to be assessed against product rules.

Categories are described in the instructions sent to the model. The Java model stores the category as text, without an application-enforced set of allowed values. A structured response format does not replace business-rule validation.

## 7. Processing and retries

| Database status | Meaning | Implemented transition |
| --- | --- | --- |
| PENDING | Message awaiting processing. | Can be completed or marked as failed. |
| COMPLETED | Analysis has been stored. | Appears in the report when its processing timestamp falls within the queried period. |
| FAILED | A processing failure has been recorded. | Can return to pending through a retry request. |

The service retrieves pending records and processes them sequentially. When text is missing, empty, or contains only whitespace, the message is marked as failed and the service advances to the next one.

Caught communication and runtime errors also lead to an attempt to mark the message as failed. Database errors propagate and can interrupt the batch; they do not receive the same per-message recovery.

On success, the repository updates the category, summary, action requirement, completed status, and processing timestamp together in one operation.

Main supports three modes:

| Mode | Application option | Result |
| --- | --- | --- |
| Normal | No options | Imports new messages and processes pending records. |
| Report | --report | Displays the report; accepts that option alone, without additional arguments. |
| Retry preparation | --retry followed by the identifier | Returns a record to pending only if it exists and is currently failed. |

Preparing a retry does not immediately run the analysis. The processing workflow must execute afterward. A negative result can mean either an unknown identifier or a record in another status; the return value does not distinguish those reasons.

Main accesses the database before validating its arguments. A report request, retry request, or invalid option therefore first attempts to open a connection. Report and retry modes return before external clients are created.

## 8. Report

The report uses completed messages whose processing timestamp falls within the previous seven days, measured from the database's current time. Results are ordered from the most recent analysis to the oldest.

It displays the identifier, sender, subject, category, summary, and action requirement. If the query returns no records, a message explains that no results were found.

The user requests this report through the terminal. There is no weekly schedule or file export yet. The seven-day window uses the processing timestamp rather than the original message receipt date.

## 9. Database

Java and the Django model use a table named emails. The following table describes the contract observed in the code, rather than an inspection of the actual database.

| Field | Purpose |
| --- | --- |
| gmail_id | Identifier used in queries and updates; declared as the primary key in the Django model. |
| sender | Sender, which may be missing. |
| subject | Subject, which may be missing. |
| processing_status | Processing state. |
| imported_at | Import timestamp. |
| processed_at | Analysis completion timestamp. |
| category | Category returned by the analysis. |
| summary | Message summary. |
| requires_action | Indicates whether action is needed; the Django model allows it to be missing before analysis. |

Java inserts only the identifier, sender, and subject. It depends on a compatible database structure, particularly suitable defaults for pending status and import timestamp. Defaults defined in the Django model do not automatically apply to records inserted directly through JDBC.

The current Java repository methods do not store the full message body.

### Table creation and migrations

The current Django model maps an existing table and is configured not to manage its structure. It can read and modify records, but table creation remains another component's responsibility, as explained in the [official Django documentation](https://docs.djangoproject.com/en/6.1/ref/models/options/#managed).

The initial migration describes an earlier model. The second migration updates Django's model state to reference the existing table without executing operations to create that table in the database.

No version-controlled script was found that creates the emails table with its current contract. Applying Django migrations therefore does not, by itself, guarantee that this table will be available in a new environment. Database provisioning remains a step to complete for a reproducible installation.

## 10. Configuration and environment setup

### Prerequisites

- A JDK compatible with the selected Gradle version and application dependencies.
- Access to the Gradle Wrapper included in the repository and to dependency resolution.
- Docker with Compose support to use the services defined in the project.
- A Google Cloud project with the Gmail API enabled and OAuth credentials for a desktop application.
- A Gmail account authorized for application access.
- An OpenAI credential with access to the configured model to run analyses.
- A PostgreSQL database containing the table expected by Java and Django.
- A Django user with the required permissions to access protected API operations.

Google credential preparation follows the conceptual flow in the [official Java quickstart](https://developers.google.com/workspace/gmail/api/quickstart/java). Check this project's own files for its specific versions and paths.

### Environment variables

| Name | Consumer | Purpose |
| --- | --- | --- |
| POSTGRES_USER | Java, API, and database service | PostgreSQL user. |
| POSTGRES_PASSWORD | Java, API, and database service | PostgreSQL password. |
| POSTGRES_DB | Java, API, and database service | Database name. |
| OPENAI_API_KEY | OpenAI client in Java | Credential for the analysis integration. |
| DJANGO_SECRET_KEY | Django API | Django configuration secret. |
| GOOGLE_CREDENTIALS_PATH | Gmail client factory | Optional OAuth file path; the code defaults to credentials.json when the variable is absent. |

The .env.example file lists variable names without secret values. GOOGLE_CREDENTIALS_PATH is used by the code but is not included in that example file. credentials.example.json shows the expected Google credential file structure without populated values.

Java reads variables from its process environment. No explicit .env loading was found in the Java application. Preparing that file for Compose does not guarantee that the same values will be available when Java runs through the IDE or Gradle.

### Addresses and ports

| Connection | Destination defined in the project |
| --- | --- |
| Java on the host to PostgreSQL | Local address 127.0.0.1, port 5433. |
| API in Compose to PostgreSQL | Service email_organizer_db, port 5432. |
| HTTP access to the API | Port 8000 published by Compose. |
| Local Google authorization callback | Port 8888. |

The default credential path and token directory are relative to the Java process's working directory. During initial authorization, the user completes Google's consent flow. Tokens are stored locally for reuse in subsequent authorization flows.

### Setup sequence without ready-to-run commands

1. Check the JDK used by the IDE and Gradle, and identify the entry point selected in the build.
2. Make the required variables available to each process.
3. Prepare the OAuth credentials and check the path expected by the Gmail client.
4. Start the Compose services and wait for the PostgreSQL health check.
5. Confirm that the emails table and its defaults exist; the repository does not fully automate this step.
6. Prepare the API user and permissions if the API will be used.
7. Run the Java test task to check the extractor.
8. Run the Main entry point in the intended mode, considering the scope of message import and analysis.
9. Check the stored processing states and inspect the report or API.

These steps were not executed while writing this documentation. A normal execution retrieves messages and sends prepared content to the analysis integration; it does more than demonstrate application startup.

## 11. Django API

The API reads and changes PostgreSQL records. It runs independently of the Java program.

| Resource | Methods or purpose | Permission |
| --- | --- | --- |
| Email collection at /api/v1/emails/ | List and create records. | Authenticated user with view or add permission. |
| Individual email under /api/v1/emails/ | Retrieve and delete using the Gmail identifier. | Authenticated user with view or delete permission. |
| /api/v1/authentication/token/ | Obtain authentication tokens. | Valid Django user credentials. |
| /api/v1/authentication/token/refresh/ | Refresh an access token. | Valid refresh token. |
| /api/v1/authentication/token/verify | Verify a token. | Token to verify; this route is declared without a trailing slash. |
| /admin/ | Django administration. | Administrative access according to the user's configuration. |

The email permissions are emails.view_emails, emails.add_emails, and emails.delete_emails. A valid token does not replace the permission required for an operation. Current email routes do not implement partial or full record updates.

The serializer exposes all model fields. The current queryset includes every record and does not filter by user ownership. Authentication controls access to operations, but the current model does not separate different users' mailboxes.

Deleting a record through the API removes the database record without calling Gmail to delete the message. Because the importer checks whether the identifier exists in the database, a message that remains in Gmail may be imported again after local deletion.

GlobalDefaultPermission exists in api/app/permissions.py, but the email views explicitly use EmailPermissionClass. The global class is not configured as the default permission in the reviewed settings.

## 12. Docker and supporting files

| File or group | Role |
| --- | --- |
| build.gradle | Plugins, dependencies, Java compatibility, entry point, and JUnit configuration. |
| settings.gradle | Gradle project name, currently quickstart. The API directory is not an active Gradle subproject. |
| gradlew and gradlew.bat | Gradle Wrapper entry points for the supported environments. |
| gradle/wrapper/gradle-wrapper.properties | Selected Gradle distribution and Wrapper configuration. |
| docker-compose.yml | API and PostgreSQL services, variables, ports, volume, and database health check. |
| api/Dockerfile | Python image, dependency installation, and Django startup. |
| api/requirements.txt | API dependencies, including style-analysis tools. |
| api/requirements_dev.txt and api/.flake8 | Python style-checking dependency and configuration. |
| api/.dockerignore | Files excluded from the API image build context. |
| .gitignore | Files and directories excluded from Git tracking. |
| .env.example and credentials.example.json | Configuration references without populated credentials. |

Compose provides the API and database. The Java application has no service in that file and runs separately. The API waits for database availability; its startup command applies migrations and launches Django's development server.

PostgreSQL uses a named data volume. The API currently has debugging enabled and an empty allowed-hosts list. These settings and the server reflect the current learning environment.

The docs directory is excluded by the reviewed local Git ignore rules and contains personal study material. Only this README is intended for the documentation commit. Ignored files such as credentials, tokens, private configuration, and build outputs were not read to prepare this README.

## 13. Tests

The project has nine Java tests in EmailBodyExtractorTest. They check:

1. Plain-text extraction.
2. HTML extraction with a link address preserved in the analysis text.
3. AMP HTML extraction with a link.
4. Empty content when the body is missing.
5. Skipping an attachment identified by its filename.
6. Skipping an attachment identified by its disposition header.
7. Combining all three body formats.
8. Reading nested parts while skipping an attachment.
9. Recognizing an uppercase content type.

The tests build messages in memory and do not call Gmail, OpenAI, or PostgreSQL. Their source is located in src/test/java/com/mayezer/emailorganizer/service/EmailBodyExtractorTest.java.

No implemented tests were found for other services, the repository, or API operations. The Django test file contains only the initial scaffold. Tests were not executed during this documentation task.

## 14. Current limitations

- Duplicate detection occurs before insertion; there is no specific handling for two concurrent importers.
- Import still accesses the payload and iterates through headers without covering every missing-data or invalid-element case.
- Sender and subject may be missing; the Java models do not enforce validation for those fields.
- An import failure can interrupt execution before processing begins.
- A database failure can interrupt the processing batch.
- The repository does not create the required table, and its current definition is not fully reproducible from version-controlled files.
- There is no persistent failure-reason field, attempt counter, or recovery schedule.
- Java uses a primitive boolean for the action requirement; the current JDBC read does not preserve the distinction between a missing database value and a negative answer.
- Current email API views have no pagination, custom query filters, or ownership separation.
- There is no visual interface, interview-tracking application integration, or Gmail label organization.

These points describe the project's current stage and provide topics for future exercises. No implementation fixes are included in this documentation update.

## 15. Product vision and next steps

The author's product diagram defines the intended flow: access Gmail, filter messages, read their content, analyze them with OpenAI, and route each group according to its purpose.

| Group | Intended destination |
| --- | --- |
| Job proposals, job updates, and recruiter messages | API integration with an external application that visually organizes interviews and their stages. |
| Event invitations | Separation as important messages. |
| Social media updates, malicious messages, and advertising | Separation for manual deletion by the user. |
| Messages grouped by subject | Organization and a weekly report. |

Entry-filter criteria, external application integration, Gmail organization, and report scheduling still need to be defined and implemented. Classification, analysis, and report retrieval already have implemented parts, as described in the previous sections.

The next technical stage is gradual Spring integration: prepare constructor-based dependencies, configure external clients and database access, register services, and connect execution modes to the new entry point. This documentation preserves the starting point before full integration.

The diagram mentions Java 25. The committed build declares compatibility with Java 11, while the local change starts the move to Java 17. These references describe different stages; a future version choice must account for the actual JDK, Gradle, and dependencies.

## 16. Study roadmap

A suggested order for reading the code:

1. **Email and EmailAnalysis:** understand data, constructors, encapsulation, and attribute access.
2. **EmailContent and AnalyzedEmail:** distinguish extracted content from associations between objects.
3. **Main:** follow the decisions and dependency assembly.
4. **Factories and DatabaseConnection:** understand external resource preparation.
5. **EmailRepository:** inspect parameters, queries, results, and resource cleanup.
6. **EmailService:** study pagination, missing lists, and duplicate prevention.
7. **EmailBodyExtractor:** study recursion, lists, maps, normalization, and missing-data handling.
8. **EmailAnalysisService:** follow how input becomes a structured analysis.
9. **EmailProcessingService:** explain each success, failure, and continuation decision.
10. **EmailReportService and tests:** follow data presentation and behavior verification.

For each class, ask: what data does it receive, which dependencies does it use, what result does it produce, what may be missing, and which failures does it handle or propagate?

Write the reasoning in your own words before implementing a change. Use the existing tests to understand observable behavior, and keep changes to business rules separate from documentation updates.
