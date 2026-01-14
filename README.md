# 🐆 Gepard Bot

**Gepard** é um Bot de Telegram inteligente desenvolvido em Java (Spring Boot) que utiliza Inteligência Artificial (Google Gemini) para agendar compromissos no Google Agenda automaticamente.

O bot é capaz de analisar **texto, áudio e imagens** para extrair detalhes de eventos (título, data, hora, localização) e criar agendamentos sem que você precise digitar manualmente.

---

## 🚀 Funcionalidades

*   **Processamento Multimodal:** Envie um áudio, uma foto de um convite ou uma mensagem de texto.
*   **Integração Google Calendar:** Cria eventos diretamente na sua agenda principal.
*   **Painel Web de Configuração:** Interface amigável para o usuário configurar sua própria API Key do Gemini e conectar sua conta Google.
*   **Painel Admin:** Área restrita para gerenciamento das chaves globais do sistema.
*   **Lembretes Inteligentes:** A IA define lembretes automaticamente ou baseada no seu pedido (ex: "me lembre 2 dias antes").

---

## 📦 Instalação e Downloads

O artefato executável (`.jar`) do projeto está disponível na aba de **Releases** do repositório GitHub.

1.  Acesse a aba [Releases](https://github.com/jjvvsszz/GepardBot/releases).
2.  Baixe a versão mais recente (ex: `Gepard-1.0.0.jar`).

---

## ⚙️ Variáveis de Ambiente (Obrigatórias)

Independentemente do modo de execução, você precisará configurar as credenciais externas.

### 1. Telegram Bot
1.  Fale com o [@BotFather](https://t.me/BotFather) no Telegram.
2.  Crie um novo bot com `/newbot`.
3.  Guarde o **Token** e o **Username**.

### 2. Google Cloud (OAuth2)
Necessário para o login social e acesso à Agenda.
1.  Acesse o [Google Cloud Console](https://console.cloud.google.com/).
2.  Crie um novo projeto.
3.  Vá em **APIs e Serviços > Biblioteca** e ative a **Google Calendar API**.
4.  Vá em **Tela de permissão OAuth**:
    *   Tipo: Externo.
    *   Adicione o escopo: `https://www.googleapis.com/auth/calendar`.
    *   Adicione seu e-mail como usuário de teste (se o app não for verificado).
5.  Vá em **Credenciais > Criar Credenciais > ID do cliente OAuth**:
    *   Tipo: Aplicação Web.
    *   **URIs de redirecionamento autorizados:** É CRUCIAL colocar a URL exata do seu bot + `/login/oauth2/code/google`.
        *   *Exemplo Local:* `http://localhost:8080/login/oauth2/code/google`
        *   *Exemplo Prod:* `https://gepard.seudominio.com/login/oauth2/code/google`
6.  Copie o **ID do Cliente** e a **Chave Secreta do Cliente**.

### 3. Google Gemini (AI)
Cada usuário configura a sua própria chave, mas o Admin pode definir um modelo padrão.
*   Obtenha a chave em: [Google AI Studio](https://aistudio.google.com/app/apikey).

---

## 🛠️ Modos de Execução

O Gepard suporta três perfis de execução controlados pela variável `SPRING_PROFILES_ACTIVE`.

### 🟢 Modo 1: Desenvolvimento (`dev`)
Ideal para testes locais. Utiliza banco de dados em memória/arquivo (H2 Database).

**Requisitos:** Java 25 instalado.

1.  Defina as variáveis de ambiente no seu terminal ou em um arquivo `.env` (se usar algum carregador):
    ```bash
    export SERVER_PORT=8080
    export APP_BASE_URL="http://localhost:8080"
    export TELEGRAM_BOT_TOKEN="seu_token_aqui"
    export TELEGRAM_BOT_USERNAME="seu_bot_user"
    export GOOGLE_CLIENT_ID="seu_client_id"
    export GOOGLE_CLIENT_SECRET="seu_client_secret"
    ```
2.  Execute o JAR:
    ```bash
    java -jar app.jar --spring.profiles.active=dev
    ```
3.  O banco H2 criará um arquivo local `./data/gepard_db`.

---

### 🔵 Modo 2: Pterodactyl (`ptero`)
Este projeto foi otimizado para rodar em painéis de hospedagem Pterodactyl.

**Instalação via Egg:**
1.  Baixe o arquivo `egg-gepard-bot.json` disponível nas Releases ou no código fonte.
2.  No painel admin do Pterodactyl, vá em **Nests > Import Egg**.
3.  Importe o arquivo JSON.
4.  Crie um novo servidor usando este Egg.

**Configuração no Painel:**
O Egg solicitará todas as variáveis necessárias na aba "Startup":
*   **Docker Image:** O Egg usa `ghcr.io/pterodactyl/yolks:java_25` (Suporte nativo ao Java 25).
*   **Database:** Crie uma Database no painel do Pterodactyl para o servidor. O Egg detectará as credenciais automaticamente.
*   **Auto-Update:** O servidor baixará e instalará a versão mais recente do repositório oficial automaticamente a cada reinicialização. Não é necessário configurar tokens do GitHub.

---

### 🔴 Modo 3: Produção / Oracle Cloud (`prod`)
Destinado a ambientes robustos usando **Oracle Autonomous Database** (OCI).

**Requisitos do Banco Oracle:**
O bot utiliza uma string de conexão JDBC específica para conexões seguras (TCPS) sem necessidade de baixar a `Wallet.zip` manualmente, usando o DN do certificado.

**Variáveis Necessárias (`prod`):**

| Variável | Descrição | Como obter no OCI |
| :--- | :--- | :--- |
| `ORACLE_HOST` | Host do banco | Na tela do DB, clique em "DB Connection". Copie o host da string (ex: `adb.sa-saopaulo-1.oraclecloud.com`). |
| `ORACLE_SERVICE_NAME` | Nome do serviço | Geralmente termina em `_high`, `_medium` ou `_low` (ex: `g12345_meubanco_high.adb...`). |
| `ORACLE_DB_USER` | Usuário | Padrão: `ADMIN`. |
| `ORACLE_DB_PASSWORD` | Senha | A senha definida na criação do Autonomous Database. |
| `ORACLE_CERT_DN` | Distinguished Name do Certificado | Encontrado na string de conexão do OCI, parâmetro `ssl_server_cert_dn`. Ex: `CN=adb.sa-saopaulo-1.oraclecloud.com, O=Oracle Corporation...` |

**Comando de Execução:**
```bash
java -jar app.jar --spring.profiles.active=prod \
  -DORACLE_HOST="adb.region.oraclecloud.com" \
  -DORACLE_SERVICE_NAME="x_high.adb..." \
  -DORACLE_CERT_DN="CN=..." \
  ... (outras variáveis)
```

---

## 🖥️ Como Utilizar

### 1. Configuração Inicial (Admin)
Assim que o bot subir pela primeira vez:
1.  Acesse `SEU_BASE_URL/admin`.
2.  Você será redirecionado para `/admin/setup`.
3.  Crie um usuário e senha para o administrador do sistema.
4.  No painel, você pode ajustar os tokens do Telegram/Google e definir o modelo de IA global (ex: Gemini Flash).

### 2. Configuração do Usuário (Telegram)
1.  Abra o bot no Telegram e clique em `Start`.
2.  Envie o comando `/config`.
3.  O bot enviará um link único e seguro. Clique para abrir.
4.  **Na página web:**
    *   Insira sua **Gemini API Key** (Gratuita no Google AI Studio).
    *   Clique em **"Conectar Google Agenda"** e autorize o acesso.
    *   (Opcional) Escolha um modelo de IA preferido (ex: Gemini Pro ou Flash Lite).

### 3. Agendando Eventos
Basta enviar mensagens para o bot:

*   **Texto:** "Jantar com a Maria sexta feira às 20h no Outback."
*   **Áudio:** Grave um áudio falando sobre o compromisso.
*   **Imagem:** Tire foto de um convite de casamento, ingresso de show ou print de e-mail.

O bot responderá com o resumo do evento criado e um link direto para o Google Agenda.

---

## 🆘 Troubleshooting

*   **Erro 400: redirect_uri_mismatch no Login Google:**
    *   Verifique se a URL em `APP_BASE_URL` é exatamente a mesma que você está usando no navegador.
    *   Confira no Google Cloud Console se a URL `SEU_DOMINIO/login/oauth2/code/google` está na lista de URIs permitidas.
*   **Java Version Error:**
    *   O projeto requer **Java 25**. Certifique-se de que o ambiente (Docker/Local) possui essa versão ou superior (Preview).
*   **Erro de Conexão Oracle:**
    *   Verifique se o `ORACLE_CERT_DN` está entre aspas duplas caso contenha espaços ou caracteres especiais ao definir a variável de ambiente.
