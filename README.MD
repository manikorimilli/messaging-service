# Messaging Service

One HTTP endpoint that sends SMS through Twilio or Vonage. Providers are described in a JSON file,
so a new provider usually needs no Java code. Java 21, Spring Boot 4.1. Design:
[docs/design.md](docs/design.md).

## How it works

```text
Client
  │  POST /api/v1/messages   { "type", "provider", "to", "body" }
  ▼
MessageController ──► MessageService ──► ProviderRegistry  (picks the provider)
                            │
                            ▼
                     MessageProvider (interface)
                            │
                     GenericHttpProvider  (one shared engine)
                     uses the provider's block in providers.json
                       ├── "twilio" block ──► Twilio API
                       └── "vonage" block ──► Vonage API
```

- Every request uses the same format, whichever provider sends it.
- `ProviderRegistry` reads `providers.json`, builds one provider per block, and checks everything
  at startup. It can also return a provider's block with its secrets masked (`****`).
- Each block says how to call that provider (URL, login, body fields) and how to read its reply.

## Quick start

1. Copy the secrets template and fill in your values. `.env` is git-ignored, so it is never
   committed:

   ```bash
   cp .env.example .env
   ```

2. Start the service on `http://localhost:8080`:

   ```bash
   ./mvnw spring-boot:run
   ```

3. Send an SMS (see [API](#api) for more examples):

   ```bash
   curl -i -X POST http://localhost:8080/api/v1/messages \
     -H 'Content-Type: application/json' \
     -d '{ "type": "SMS", "provider": "TWILIO", "to": "+91XXXXXXXXXX", "body": "Hello!" }'
   ```

The app checks its configuration at startup and refuses to start, with a clear message, if a
secret is empty, a provider block is incomplete, or the routing is wrong.

## Configuration

| File | Holds |
|---|---|
| `src/main/resources/providers.json` | Routing (`channels`) and one block per provider (`http-providers`) |
| `.env` | Secrets and sender numbers (never committed) |
| `.env.example` | The list of variable names, without values (committed) |
| `src/main/resources/application.properties` | HTTP timeouts (2s connect, 10s read) |

### Secrets (`.env`)

```properties
TWILIO_ACCOUNT_SID=<your-account-sid>
TWILIO_API_KEY=<your-api-key-sid>
TWILIO_API_SECRET=<your-api-key-secret>
TWILIO_SMS_FROM=<your-twilio-number>
VONAGE_API_KEY=<your-api-key>
VONAGE_API_SECRET=<your-api-secret>
VONAGE_SMS_FROM=<your-sender-name-or-number>
```

| Variable | Where to find it |
|---|---|
| `TWILIO_ACCOUNT_SID` | Twilio Console, Account info (starts with `AC`) |
| `TWILIO_API_KEY`, `TWILIO_API_SECRET` | Twilio Console, API keys & tokens (key starts with `SK`) |
| `TWILIO_SMS_FROM` | Your Twilio phone number, in E.164 format |
| `VONAGE_API_KEY`, `VONAGE_API_SECRET` | Vonage Dashboard, API settings |
| `VONAGE_SMS_FROM` | Your Vonage number, or a sender name of up to 11 characters |

Real environment variables with the same names override `.env`; use those on servers. Secrets are
never logged and never returned by the API.

### Routing (`providers.json`)

```json
"channels": { "sms": { "primary-provider": "TWILIO", "enabled-providers": ["TWILIO", "VONAGE"] } }
```

- Requests without `provider` use the primary.
- A provider not in `enabled-providers` needs no secrets; requests naming it get `503`.
- Override without editing files:

  ```bash
  MESSAGING_CHANNELS_SMS_PRIMARYPROVIDER=VONAGE ./mvnw spring-boot:run
  ```

  ```bash
  MESSAGING_CHANNELS_SMS_ENABLEDPROVIDERS=TWILIO ./mvnw spring-boot:run
  ```

## API

`POST /api/v1/messages`

| Field | Required | Rules |
|---|---|---|
| `type` | yes | `SMS`, any letter case |
| `provider` | no | `TWILIO` or `VONAGE`, any letter case; omitted means the primary |
| `to` | yes | E.164: `+`, country code, number (8 to 15 digits) |
| `body` | yes | Not blank, at most 1600 characters |

Through a chosen provider:

```bash
curl -i -X POST http://localhost:8080/api/v1/messages \
  -H 'Content-Type: application/json' \
  -d '{ "type": "SMS", "provider": "VONAGE", "to": "+91XXXXXXXXXX", "body": "Hello from Vonage" }'
```

Through the primary provider:

```bash
curl -i -X POST http://localhost:8080/api/v1/messages \
  -H 'Content-Type: application/json' \
  -d '{ "type": "SMS", "to": "+91XXXXXXXXXX", "body": "Your verification code is 482913" }'
```

Success is `202 Accepted`. It means the provider accepted the SMS, not that the phone received it:

```json
{
  "status": "ACCEPTED",
  "provider": "TWILIO",
  "providerMessageId": "SM2c7f9a1e0b3d4c5e6f7a8b9c0d1e2f3a",
  "providerStatus": "queued",
  "acceptedAt": "2026-10-15T10:15:30Z"
}
```

`providerMessageId` is the provider's own id; use it to find the message in the Twilio or Vonage
dashboard.

### Errors

Every error is `application/problem+json` with a stable `code`:

| `code` | HTTP | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Missing or invalid field, unknown `type`, malformed JSON |
| `UNSUPPORTED_PROVIDER` | 400 | Unknown provider |
| `UNSUPPORTED_MESSAGE_TYPE` | 400 | The provider cannot send this `type` |
| `PROVIDER_UNAVAILABLE` | 503 | Provider disabled, unreachable, throttled, or down |
| `PROVIDER_TIMEOUT` | 504 | No reply in time; the SMS may or may not have been sent |
| `PROVIDER_REJECTED` | 502 | Provider refused (bad number, credentials, trial limits) |
| `INTERNAL_ERROR` | 500 | Unexpected; details only in the logs |

Example:

```json
{
  "title": "Provider rejected the message",
  "status": 502,
  "detail": "Twilio rejected the message: The 'To' number is not a valid phone number.",
  "instance": "/api/v1/messages",
  "code": "PROVIDER_REJECTED",
  "provider": "TWILIO",
  "providerErrorCode": "21211"
}
```

### Troubleshooting

| You see | Cause | Fix |
|---|---|---|
| App won't start: `... is enabled for SMS but its credentials are missing` | An empty variable in `.env` | Fill it in, or remove the provider from `enabled-providers` |
| `502`, `providerErrorCode` `21608` (Twilio) | Trial account, number not verified | Verify the number in the Twilio Console |
| `502`, `providerErrorCode` `29` (Vonage) | Demo account, number not whitelisted | Add it as a test number in the Vonage Dashboard |
| `502`, `providerErrorCode` `20003` (Twilio) or `4` (Vonage) | Wrong API key or secret | Check `.env` |
| `504 PROVIDER_TIMEOUT` | The provider didn't answer in 10 seconds | Check the provider's dashboard before resending |

## Adding a provider

Most providers need **no Java code**:

1. Add a block under `http-providers` in `providers.json`.
2. Add its id to `channels.sms.enabled-providers`.
3. Add its secrets to `.env` and their names to `.env.example`.

```json
"example": {
  "id": "EXAMPLE",
  "display-name": "Example",
  "channels": ["SMS"],
  "variables": { "api-key": "${EXAMPLE_API_KEY:}", "from": "${EXAMPLE_SMS_FROM:}" },
  "request": {
    "method": "POST",
    "url": "https://api.example-sms.com/v1/send",
    "auth": { "type": "bearer", "token": "{{api-key}}" },
    "body": { "format": "json", "fields": { "src": "{{from}}", "dst": "{{to}}", "text": "{{text}}" } }
  },
  "response": {
    "success": { "http-status": ["2xx"] },
    "id-path": "$.message_id",
    "error": { "code-path": "$.error_code", "message-path": "$.error" },
    "retry": { "http-status": ["429", "5xx"] }
  }
}
```

Secrets go in `variables` as `"${ENV_VAR:}"` and are used as `{{name}}`. Never write a real secret
directly in `providers.json`.

| Setting | Meaning |
|---|---|
| `id`, `display-name` | Id used in requests and routing; name used in error messages (defaults to the id) |
| `channels` | Channels the provider can send, e.g. `["SMS"]` |
| `variables` | Secrets and settings; all must be filled when the provider is enabled |
| `request.method`, `request.url` | `POST`, `PUT` or `PATCH`; the URL may only use variables, never message values |
| `request.auth.type` | `basic` (`username`, `password`), `bearer` (`token`), `header` (`header-name`, `header-value`) or `none` |
| `request.body.format` | `form` or `json` |
| `request.body.fields` | Body fields and their templates |
| `request.body.unicode-fields` | Extra fields added only for non-GSM text (Hindi, emoji) |
| `response.success.http-status` | Statuses that may mean success; default `["2xx"]` |
| `response.success.each`, `field`, `expected` | For replies where HTTP 200 can still be a failure: every item must have `field` equal to `expected` |
| `response.id-path`, `status-path` | JSONPath to the message id and the provider's status |
| `response.error.code-path`, `message-path` | JSONPath to the error code and text |
| `response.retry.http-status`, `codes` | What means "try later": statuses (default `["429", "5xx"]`) and provider error codes |

| Placeholder | Value |
|---|---|
| `{{to}}` | recipient with `+`, e.g. `+91XXXXXXXXXX` |
| `{{to_digits}}` | recipient without `+`, e.g. `91XXXXXXXXXX` |
| `{{text}}` | message body |
| `{{<name>}}` | any value from the block's `variables` |

The `twilio` and `vonage` blocks are complete working examples. Vonage shows how to handle a
provider's quirks with settings only: `{{to_digits}}` (no `+`), `unicode-fields` (`type=unicode`),
`success.each` (HTTP 200 replies that can still fail) and `retry.codes` (`1` throttled, `5`
internal error).

A provider that can't be described this way (vendor SDK, JWT signing) can be a Java class that
implements `MessageProvider` and is annotated `@Component`.

## Project structure

```text
src/main/java/com/example/messaging_service/
├── controller/      MessageController: POST /api/v1/messages
├── dto/             request, response, MessageType
├── service/         MessageService (the send flow), ProviderRegistry (owns all providers)
├── provider/        MessageProvider interface, OutboundMessage, ProviderResult
│   └── generic/     GenericHttpProvider engine, config model, validation, templates, reply reading
└── exception/       error codes, MessagingException, GlobalExceptionHandler

src/main/resources/
├── providers.json            routing + provider blocks
└── application.properties    HTTP timeouts, imports .env and providers.json
```

## Tests

```bash
./mvnw test
```

No test calls Twilio or Vonage: provider HTTP calls are mocked, and no `.env` is needed.
