# 🐆 Gepard Bot

**Gepard** é um Bot de Telegram inteligente desenvolvido em Java (Spring Boot) que utiliza Inteligência Artificial (Google Gemini e DeepSeek) para agendar compromissos no Google Agenda automaticamente.

O bot é capaz de analisar **texto, áudio e imagens** para extrair detalhes de eventos (título, data, hora, localização) e criar agendamentos sem que você precise digitar manualmente.

---

## 🚀 Funcionalidades

*   **Processamento Multimodal:** Envie áudio, foto de um convite ou mensagem de texto.
*   **Integração Google Calendar:** Cria, lista e deleta eventos diretamente na sua agenda principal.
*   **Confirmação antes de criar:** O bot mostra o resumo do evento e pede confirmação com botões inline.
*   **Múltiplos Modelos de IA:** Suporte a Gemini (texto/foto/áudio) e DeepSeek (texto).
*   **Roteamento automático:** Mídia → Gemini; Texto puro → modelo escolhido pelo usuário.
*   **Painel Web de Configuração:** Interface para configurar API Key, escolher modelo e conectar Google.
*   **Painel Admin:** Área restrita para gerenciamento global e visualização de usuários.
*   **Lembretes Inteligentes:** A IA define lembretes automaticamente com base no seu pedido.
*   **Comandos do Bot:** `/start`, `/eventos`, `/config`, `/cancelar`, `deletar N`.

### Modelos de IA Disponíveis

| Modelo | Provedor | Suporte |
|---|---|---|
| `models/gemini-3.1-flash-lite-preview` | Google (padrão) | Texto, foto, áudio |
| `models/gemini-3-flash-preview` | Google | Texto, foto, áudio |
| `models/gemini-3.1-pro-preview` | Google | Texto, foto, áudio |
| `deepseek-v4-flash` | DeepSeek | Apenas texto |
| `deepseek-v4-pro` | DeepSeek | Apenas texto |

> DeepSeek é usado apenas para mensagens de texto. Se o usuário enviar foto/áudio com DeepSeek selecionado, o bot usa Gemini automaticamente.

---

## 📦 Instalação e Downloads

O artefato executável (`.jar`) está na aba de **Releases** do repositório GitHub.

1.  Acesse [Releases](https://github.com/jjvvsszz/GepardBot/releases).
2.  Baixe a versão mais recente (ex: `Gepard-1.0.0.jar`).

---

## ⚙️ Variáveis de Ambiente (Obrigatórias)

### 1. Telegram Bot
1.  Fale com o [@BotFather](https://t.me/BotFather) no Telegram.
2.  Crie um novo bot com `/newbot`.
3.  Guarde o **Token** e o **Username**.

### 2. Google Cloud (OAuth2)
1.  Acesse o [Google Cloud Console](https://console.cloud.google.com/).
2.  Crie um projeto e ative a **Google Calendar API**.
3.  Configure a **Tela de permissão OAuth** (tipo Externo, escopo `calendar`).
4.  Crie credencial **OAuth 2.0** tipo Aplicação Web.
5.  **URI de redirecionamento:** `{SEU_DOMINIO}/login/oauth2/code/google`.
6.  Copie o **ID do Cliente** e a **Chave Secreta**.

### 3. API Keys (IA)
Cada usuário configura sua própria chave no painel web:
*   **Gemini:** [Google AI Studio](https://aistudio.google.com/app/apikey) — chave começa com `AIza...`
*   **DeepSeek:** [DeepSeek Platform](https://platform.deepseek.com/api_keys) — chave começa com `sk-...`

---

## 🛠️ Modos de Execução

Controlados pela variável `SPRING_PROFILES_ACTIVE`:

### 🟢 Desenvolvimento (`dev`)
Banco H2 local (`./data/gepard_db`). Requer Java 25.

```bash
export SERVER_PORT=8080
export APP_BASE_URL="http://localhost:8080"
export TELEGRAM_BOT_TOKEN="seu_token"
export TELEGRAM_BOT_USERNAME="seu_bot"
export GOOGLE_CLIENT_ID="seu_client_id"
export GOOGLE_CLIENT_SECRET="seu_client_secret"
export APP_ENCRYPTION_KEY="uma-chave-de-exatamente-32-bytes!"

java -jar app.jar --spring.profiles.active=dev
```

### 🔵 Pterodactyl (`ptero`)
Importe o arquivo `egg-gepard-bot.json` no painel Pterodactyl (Nests > Import Egg).
Docker image: `ghcr.io/pterodactyl/yolks:java_25`.

### 🔴 Produção / Oracle Cloud (`prod`)
Oracle Autonomous Database via TCPS.

```bash
java -jar app.jar --spring.profiles.active=prod \
  -DORACLE_HOST="adb.region.oraclecloud.com" \
  -DORACLE_SERVICE_NAME="x_high.adb..." \
  -DORACLE_CERT_DN="CN=..." \
  -DORACLE_DB_USER="ADMIN" \
  -DORACLE_DB_PASSWORD="senha"
```

---

## 🖥️ Como Utilizar

### 1. Configuração Inicial (Admin)
1. Acesse `{BASE_URL}/admin`.
2. Crie usuário e senha no setup inicial.
3. No painel, configure tokens Telegram/Google e modelo de IA padrão.

### 2. Configuração do Usuário
1. No Telegram, digite `/start` para ver instruções.
2. Digite `/config` para receber link do painel web.
3. Insira sua API Key (Gemini ou DeepSeek).
4. Conecte sua conta Google.
5. Escolha seu modelo de IA preferido.

### 3. Agendando Eventos
Envie mensagens para o bot:
*   **Texto:** "Jantar com Maria sexta 20h no Outback"
*   **Áudio:** Grave um áudio descrevendo o compromisso
*   **Foto:** Envie foto de convite, ingresso ou print de e-mail
*   O bot mostra o resumo e pede confirmação antes de criar

### 4. Gerenciando Eventos
*   `/eventos` — Lista próximos 10 eventos com índices
*   `deletar 3` — Deleta o evento número 3 da lista
*   `/cancelar` — Cancela operação pendente

---

## 🔒 Segurança

*   Dados sensíveis (API keys, tokens Google) são criptografados com **AES-256-GCM**.
*   Configure `APP_ENCRYPTION_KEY` com exatamente 32 caracteres em produção.
*   Tokens de acesso web expiram em 24 horas.

---

## 🆘 Troubleshooting

*   **Erro 400: redirect_uri_mismatch:** Verifique `APP_BASE_URL` e a URI no Google Cloud Console.
*   **Java Version Error:** Requer **Java 25**. Use Docker ou instale a versão correta.
*   **Erro Oracle:** Certifique-se de que `ORACLE_CERT_DN` está entre aspas.
