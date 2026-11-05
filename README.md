# CommitNotes

Aplicação web para documentação e acompanhamento de projetos em equipe. O frontend usa Vue 3, Vite, Vue Router, Pinia e **JavaScript**. A API usa Java 21, Spring Boot e PostgreSQL. A interface usa ideias de notas conectadas e quadros Kanban sem reproduzir marcas ou telas de outros produtos. Pode ser instalada como aplicativo (PWA) e os ícones são SVG ([Lucide](https://lucide.dev), licença ISC).

## Início rápido com Docker

1. Copie `.env.example` para `.env` e substitua `DB_PASSWORD`, `APP_SECRET` (mínimo 32 caracteres) e `ADMIN_PASSWORD` (mínimo 12 caracteres). Altere `ADMIN_EMAIL` para seu endereço. Nunca publique esse arquivo.
2. Execute `docker compose up --build -d` na raiz.
3. Abra `http://localhost:8088`. Consulte `docker compose logs -f api` se a migração não iniciar.
4. Para parar sem apagar dados: `docker compose down`. Os volumes `postgres_data` e `files_data` guardam banco e uploads. **Não use `down -v` para uma instalação com dados.**

O frontend é servido pelo Nginx; `/api` é encaminhado para o Spring Boot. A porta pública pode ser alterada em `WEB_PORT`. Em produção, publique o frontend atrás de HTTPS, defina `APP_ORIGIN` com a URL pública HTTPS e use senhas exclusivas. HTTPS também é requisito para instalar o PWA e receber notificações push.

### VPS com EasyPanel e Traefik

O arquivo `compose.prod.yaml` conecta somente o frontend à rede externa `easypanel`, remove a publicação da porta local e anuncia uma rota HTTPS para `COMMITNOTES_DOMAIN`; o Traefik redireciona HTTP para HTTPS e emite o certificado Let's Encrypt. O Nginx preserva o protocolo HTTPS recebido do Traefik ao encaminhar a API, para que os cookies de sessão tenham o atributo `Secure`, e envia `Strict-Transport-Security` (HSTS) somente quando a requisição chegou por HTTPS, para que o navegador passe a usar sempre o endereço seguro.

Defina no `.env` `COMMITNOTES_DOMAIN`, `APP_ORIGIN=https://<domínio>`, `COMPOSE_FILE=compose.yaml:compose.prod.yaml`, segredos únicos e, se disponíveis, SMTP e VAPID. Execute sempre os dois arquivos juntos:

```sh
docker compose -f compose.yaml -f compose.prod.yaml config --quiet
docker compose -f compose.yaml -f compose.prod.yaml up -d --build
docker compose -f compose.yaml -f compose.prod.yaml ps
```

Para um novo deploy, preserve `.env`, os volumes `postgres_data` e `files_data` e o arquivo `compose.prod.yaml`. Não use `down -v`. O banco e a API ficam apenas na rede interna do projeto; o Traefik encaminha para a porta 80 do container `web`.

## Desenvolvimento local

Requer Java 21, Maven 3.9+, Node.js 22+, npm e PostgreSQL 16+.

1. Crie o banco `commitnotes` e o usuário `commitnotes` no PostgreSQL. Configure `DB_URL`, `DB_USER`, `DB_PASSWORD` e `APP_SECRET` no ambiente. Para o fluxo de recuperação, configure SMTP; para push, configure VAPID.
2. Em `backend/`, execute `mvn spring-boot:run`.
3. Em `frontend/`, execute `npm ci` e `npm run dev`. Abra `http://localhost:5173`. O proxy Vite encaminha `/api` para `localhost:8080`. Defina `APP_ORIGIN=http://localhost:5173` para links de recuperação.

Para uma inspeção local sem Docker e sem configurar um banco, execute em `backend/` o comando `mvn test-compile exec:java '-Dexec.mainClass=com.commitnotes.DevLauncher' '-Dexec.classpathScope=test'` e depois inicie o frontend Vite. Esse lançador usa PostgreSQL temporário e um segredo de teste; os dados são descartados ao encerrá-lo e ele não deve ser publicado.

## Configuração adicional

- **Recuperação de senha:** configure `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS` e `MAIL_FROM`. O token aleatório é armazenado somente como SHA-256, expira em 30 minutos, só pode ser usado uma vez e invalida as sessões existentes. A resposta da solicitação não revela se a conta existe.
- **Push em segundo plano:** configure `VAPID_PUBLIC`, `VAPID_PRIVATE` e `VAPID_SUBJECT`. Gere um par VAPID com uma ferramenta Web Push confiável, por exemplo `npx web-push generate-vapid-keys`. O navegador precisa de HTTPS (ou `localhost`), permissão de notificação e inscrição ativada pelo usuário em Lembretes > Ativar notificações; no iPhone, o app precisa estar instalado na tela de início. O push leva lembretes vencidos e as atualizações de tarefas. A aplicação também consulta lembretes vencidos e notificações para exibir os avisos internos quando aberta.
- **Administração:** `ADMIN_EMAIL` e `ADMIN_PASSWORD` criam o primeiro administrador se a conta ainda não existir. O Painel DEV gerencia contas e papéis globais, mas todas as APIs de notas, arquivos e tarefas continuam exigindo participação no projeto.
- **Armazenamento:** `FILES_DIR` aponta para o volume dos arquivos; metadados e texto indexável ficam no PostgreSQL. Arquivos permitidos: PDF, DOCX, TXT, PNG, JPG e WEBP. O limite por arquivo é `MAX_UPLOAD_MB` (1 a 100, padrão 20); a interface consulta `/api/files/limits` e recusa arquivos maiores ou de outro formato antes de enviar, e o servidor repete a verificação de tamanho, assinatura e formato. O texto de documentos é extraído para busca.
- **Aplicativo (PWA):** `public/manifest.webmanifest` e o service worker gerado no build (`src/sw.js`, emitido como `/sw.js` pelo `vite.config.js`) permitem instalar o CommitNotes e abri-lo sem conexão; páginas são buscadas primeiro na rede, então cada deploy chega na próxima abertura. Os ícones PNG em `public/icons` são gerados por `node scripts/generate-icons.mjs`.

## Modelo e integrações

- `app_user`, `reset_token`, `auth_throttle` e `push_subscription`: identidade, preferências, recuperação e notificações.
- `project` e `project_member`: isolamento por projeto e papéis `OWNER`, `EDITOR`, `READER`. O papel é checado na API em cada operação.
- `folder`, `tag`, `note`, `note_version`, `note_link`: organização, histórico, links e referências de volta. Etiquetas têm cor, usada na lista de notas, no painel do projeto e na visão geral. Notas usam versão otimista; uma gravação baseada em versão antiga retorna HTTP 409 e preserva o rascunho no navegador.
- `board`, `board_column`, `task`, `task_assignee`, `task_step`, `task_note`, `task_comment`: Kanban, responsáveis (vários por tarefa), prazos, prioridades, etapas, vínculos e comentários. Colunas têm cor, posição e podem ser marcadas como de conclusão; podem ser recolhidas lateralmente no computador e, no celular, escolhidas por um seletor. Ao excluir uma coluna com tarefas, elas são movidas para a coluna escolhida. O percentual é calculado pelas etapas concluídas.
- `notification`: cada atualização de uma tarefa (status, prazo, responsáveis, etapas, comentários, notas e anexos) gera um aviso para todos os responsáveis e para quem criou a tarefa, exceto o autor da mudança. Os avisos aparecem no sino e também via push; o modal da tarefa mostra a linha completa de atualizações.
- `stored_file`, `file_link`: bytes em volume, metadados no banco e vínculos a notas/tarefas. A busca consulta nome e texto extraído.
- `reminder`: avisos pessoais opcionais vinculados a projeto/nota, independentes das tarefas. O agendador do backend envia Web Push e o frontend mostra avisos internos.
- `activity` e `admin_audit`: histórico de mudanças do projeto e ações globais administrativas. A linha do tempo combina atividades com uma visão de prazos das tarefas.

As migrações Flyway estão em `backend/src/main/resources/db/migration`. O Markdown é sanitizado antes da visualização. Links dentro do conteúdo das notas são inseridos pelo popup do editor (digite `[[`, use Ctrl+K ou o botão Link), que permite buscar notas, arquivos e lembretes, enviar um arquivo novo ou criar um lembrete na hora:

- nota: `[[ID-da-nota|Título]]` (também gera referência de volta);
- arquivo: `[[file:ID-do-arquivo|nome.pdf]]` (abre a prévia);
- lembrete: `[[reminder:ID-do-lembrete|Título]]` (abre o lembrete; lembretes são pessoais).

A **Visão geral** das notas (botão em Notas) mostra a teia de notas com cores por etiqueta ou pasta, legenda que filtra por categoria, prévia com criador e datas ao passar o mouse (ou tocar) e destaque das ligações da nota.

## Testes e verificações

```text
cd backend
mvn test                # validações unitárias
mvn verify              # inclui fluxos HTTP com PostgreSQL embutido de teste

cd ../frontend
npm ci
npm test
npm run build
npm audit --omit=dev
```

O teste de integração exercita cadastro, compartilhamento e leitura, bloqueio do leitor na escrita, isolamento de projeto, versões e conflito de nota, upload e busca de texto, limite de upload, quadro, colunas (cor, ordem e exclusão), responsáveis, comentários e notificações, etiquetas coloridas na visão geral e lembrete. Ele inicia uma instância temporária de PostgreSQL sem Docker. Os testes do frontend cobrem links das notas, validação de uploads, layout da visão geral e textos das atualizações. Para testar manualmente, crie duas contas, compartilhe um projeto, atribua uma tarefa às duas e altere-a com uma delas: a outra recebe o aviso no sino.

## Limites operacionais

- A recuperação depende de um servidor SMTP funcional. Sem ele, a solicitação recebe resposta genérica, mas o link não pode ser entregue; verifique os logs do backend.
- Push fora da página depende de HTTPS, VAPID, permissão do usuário, serviço push do navegador e backend em execução. O popup interno não exige push.
- A visão geral usa as relações entre notas do projeto aberto (modelos não aparecem). A edição simultânea é protegida por detecção de conflito e recarga manual; não há mesclagem em tempo real. O quadro Kanban se atualiza a cada 30 segundos.
- A prévia interna cobre imagens (com zoom), PDF (visualizador do navegador no computador e pdf.js no celular, até 40 páginas), TXT e DOCX (renderizado no navegador; layouts complexos podem diferir do Word). Os visualizadores de PDF e DOCX só são baixados na primeira prévia.
- Offline, o aplicativo abre e informa a falta de conexão, mas dados e alterações dependem do servidor.
"# CommitNotes" 
