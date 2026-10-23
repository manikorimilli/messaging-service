# Messaging Service

Sends SMS through Twilio or Vonage: one number at a time, a batch of numbers, or a daily job that
reads a file of numbers. The design is in [docs/design.md](docs/design.md); the batch feature is in
[docs/batching-feature.md](docs/batching-feature.md).

Java 21, Spring Boot 4.1.

## Configure

- `src/main/resources/providers.json`: everything about providers, in one file:
  - `channels`: routing (which providers are enabled per channel, which is the default);
  - `http-providers`: one block per provider describing how to call it (URL, login, body fields)
    and how to read its replies. See [Adding a provider](#adding-a-provider).
- `src/main/resources/application.properties`: HTTP timeouts, and the import of `.env` and
  `providers.json`.

`ProviderRegistry` owns this file: it builds one provider per block, checks everything at
startup, and can give back any registered provider's block with its secrets masked
(`registry.description(MessageType.SMS, "TWILIO")`, `registry.descriptions(MessageType.SMS)`).

Secrets come from environment variables, or from a local `.env` file:

```bash
cp .env.example .env
```

Then fill in `.env`. It is git-ignored. Real environment variables with the same names override it.

| Variable | Used for |
|---|---|
| `TWILIO_ACCOUNT_SID` | Twilio Account SID, `AC...` (Console, Account info) |
| `TWILIO_API_KEY`, `TWILIO_API_SECRET` | Twilio API key SID `SK...` and its secret (Console, API keys & tokens). For local testing you may use the Account SID and Auth Token instead. |
| `TWILIO_SMS_FROM` | Your Twilio phone number, e.g. `+15551234567` |
| `VONAGE_API_KEY`, `VONAGE_API_SECRET` | Vonage credentials (Dashboard, API settings) |
| `VONAGE_SMS_FROM` | Your Vonage number or sender name |

The app checks the configuration at startup and refuses to start, with a clear message, when an
enabled provider has an empty variable, a provider block is incomplete, or the routing is wrong.

### Routing

In `providers.json`:

```json
"channels": {
  "sms": { "primary-provider": "TWILIO", "enabled-providers": ["TWILIO", "VONAGE"] }
}
```

- Requests without `provider` use the primary. Switch it with
  `MESSAGING_CHANNELS_SMS_PRIMARYPROVIDER=VONAGE`, no code change.
- To use only Twilio, set `MESSAGING_CHANNELS_SMS_ENABLEDPROVIDERS=TWILIO`. Vonage then needs no
  credentials, and requests that name it get `503 PROVIDER_UNAVAILABLE`.

## Run

```bash
export TWILIO_ACCOUNT_SID=AC...
export TWILIO_API_KEY=SK...
export TWILIO_API_SECRET=...
export MESSAGING_PROVIDERS_TWILIO_SMSFROM=+1...
export VONAGE_API_KEY=...
export VONAGE_API_SECRET=...
export MESSAGING_PROVIDERS_VONAGE_SMSFROM=...
./mvnw spring-boot:run
```

The service listens on `http://localhost:8080`.

## Send a message

With an explicit provider:

```bash
curl -i -X POST http://localhost:8080/api/v1/messages \
  -H 'Content-Type: application/json' \
  -d '{ "type": "SMS", "provider": "TWILIO", "to": "+919876543210", "body": "Welcome to our platform!" }'
```

With the primary provider:

```bash
curl -i -X POST http://localhost:8080/api/v1/messages \
  -H 'Content-Type: application/json' \
  -d '{ "type": "SMS", "to": "+919876543210", "body": "Your verification code is 482913" }'
```

| Field | Required | Rules |
|---|---|---|
| `type` | yes | `SMS`, any letter case |
| `provider` | no | `TWILIO` or `VONAGE`, any letter case |
| `to` | yes | E.164: `+`, country code, number (8 to 15 digits) |
| `body` | yes | Not blank, at most 1600 characters |

Success is `202 Accepted`:

```json
{
  "status": "ACCEPTED",
  "provider": "TWILIO",
  "providerMessageId": "SM2c7f9a1e0b3d4c5e6f7a8b9c0d1e2f3a",
  "providerStatus": "queued",
  "acceptedAt": "2026-10-14T10:15:30Z"
}
```

`ACCEPTED` means the provider took the message, not that the phone received it. Delivery tracking
is not part of this service yet.

## Send to many numbers (batch)

One body to up to 100 numbers, all through one provider:

```bash
curl -i -X POST http://localhost:8080/api/v1/messages/batch \
  -H 'Content-Type: application/json' \
  -d '{ "type": "SMS", "provider": "TWILIO", "recipients": ["+91XXXXXXXXXX", "+91YYYYYYYYYY"], "body": "Your bill is ready" }'
```

- `provider` is optional (the primary is used). `recipients` holds 1 to 100 E.164 numbers
  (`messaging.batch.max-recipients`); more gives `400 VALIDATION_FAILED` and nothing is sent.
- Numbers are sent one after another. A repeated number is sent once. A failed number is not
  retried and does not stop the others.
- `200 OK` means the batch ran. Check `failed` and each result:

```json
{
  "provider": "TWILIO",
  "total": 2,
  "accepted": 1,
  "failed": 1,
  "duplicatesRemoved": 0,
  "results": [
    { "to": "+91XXXXXXXXXX", "status": "ACCEPTED", "providerMessageId": "SM...", "providerStatus": "queued" },
    { "to": "+91YYYYYYYYYY", "status": "FAILED", "code": "PROVIDER_REJECTED", "providerErrorCode": "21608",
      "detail": "Twilio rejected the message: ..." }
  ]
}
```

## Daily job

Every day at 10:00 IST the job reads `batch-targets.json` (numbers grouped by provider, one body
per group) and sends each group through the batch service, 100 numbers at a time. Results go to
the logs, and every run is added to `jobHistory.json`. It is **off by default**.

```json
{
  "version": "2026-10-20",
  "groups": [
    { "provider": "TWILIO", "body": "Your bill is ready", "recipients": ["+91XXXXXXXXXX"] },
    { "provider": "VONAGE", "body": "Your order has shipped", "recipients": ["+91YYYYYYYYYY"] }
  ]
}
```

| Setting | Default | Meaning |
|---|---|---|
| `messaging.batch-job.enabled` | `false` | Turn the job on (`MESSAGING_BATCHJOB_ENABLED=true`) |
| `messaging.batch-job.cron` | `0 0 10 * * *` | When it runs (second minute hour day month weekday) |
| `messaging.batch-job.zone` | `Asia/Kolkata` | Time zone for the cron |
| `messaging.batch-job.targets-file` | `classpath:batch-targets.json` | The file inside the project. Use `file:/path/batch-targets.json` for a file on disk that is replaced daily. |
| `messaging.batch-job.history-file` | `./jobHistory.json` | Where each run is recorded (`MESSAGING_BATCHJOB_HISTORYFILE`). One entry per run, oldest first. |

The file is read again on every run, so a replaced file is picked up without a restart. If the
app is not running at 10:00, that day is skipped.

## Errors

Every error is `application/problem+json` with a stable `code`:

| `code` | HTTP | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Missing or invalid field, unknown `type`, malformed JSON |
| `UNSUPPORTED_PROVIDER` | 400 | No adapter with that provider id |
| `UNSUPPORTED_MESSAGE_TYPE` | 400 | The provider cannot send this `type` |
| `PROVIDER_UNAVAILABLE` | 503 | Provider disabled, unreachable, throttled, or returned 5xx |
| `PROVIDER_TIMEOUT` | 504 | No reply in time; the message may or may not have been sent |
| `PROVIDER_REJECTED` | 502 | Provider refused the message (bad number, auth, trial limits) |
| `PROVIDER_NOT_FOUND` | 404 | `/api/v1/providers/{id}`: no provider with that id |
| `PROVIDER_ALREADY_EXISTS` | 409 | Registering an id that is already used |
| `PROVIDER_READ_ONLY` | 409 | Changing or removing a provider from `providers.json` |
| `PROVIDER_IN_USE` | 409 | Removing a provider named in the `providers.json` routing |
| `INTERNAL_ERROR` | 500 | Anything unexpected; details are only in the logs |

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

## Trial accounts

- **Twilio trial** sends only to phone numbers verified in your Twilio Console. Others are
  rejected with error `21608`.
- **Vonage demo mode** sends only to whitelisted test numbers. Others are rejected with status `29`.

## Tests

```bash
./mvnw test
```

The tests never call Twilio or Vonage; provider HTTP calls are mocked.

## Adding a provider

Most providers need **no Java code**. Add a block under `messaging.http-providers` in
`src/main/resources/providers.json`:

```json
"example": {
  "id": "EXAMPLE",
  "display-name": "Example",
  "channels": ["SMS"],
  "variables": {
    "api-key": "${EXAMPLE_API_KEY:}",
    "from": "${EXAMPLE_SMS_FROM:}"
  },
  "request": {
    "method": "POST",
    "url": "https://api.example-sms.com/v1/send",
    "auth": { "type": "bearer", "token": "{{api-key}}" },
    "body": {
      "format": "json",
      "fields": { "src": "{{from}}", "dst": "{{to}}", "text": "{{text}}" }
    }
  },
  "response": {
    "success": { "http-status": ["2xx"] },
    "id-path": "$.message_id",
    "error": { "code-path": "$.error_code", "message-path": "$.error" },
    "retry": { "http-status": ["429", "5xx"] }
  }
}
```

Then add `EXAMPLE` to `channels.sms.enabled-providers` in the same file, and its secrets to `.env`.

### What each part means

| Setting | Meaning |
|---|---|
| `id`, `display-name` | Id used in requests and routing; name used in error messages (defaults to the id). |
| `channels` | Channels the provider can send, e.g. `["SMS"]`. |
| `variables` | Secrets and settings, usually `"${ENV_VAR:}"`. Every one must have a value when the provider is enabled. |
| `request.method`, `request.url` | `POST`, `PUT` or `PATCH`, and the URL. The URL may only use variables, never message values. |
| `request.auth.type` | `basic` (`username`, `password`), `bearer` (`token`), `header` (`header-name`, `header-value`) or `none`. |
| `request.body.format` | `form` or `json`. |
| `request.body.fields` | Body fields with templates. |
| `request.body.unicode-fields` | Extra fields added only when the text is not plain GSM-7 (Hindi, emoji, ...). |
| `response.success.http-status` | Statuses that may mean success; default `["2xx"]`. |
| `response.success.each`, `field`, `expected` | For replies where HTTP 200 can still mean failure: every item in the list must have `field` equal to `expected`. |
| `response.id-path`, `status-path` | JSONPath to the message id and the provider's status. |
| `response.error.code-path`, `message-path` | JSONPath to the error code and text. |
| `response.retry.http-status`, `codes` | What means "try later": statuses (default `["429", "5xx"]`) and provider error codes. |

Placeholders in templates:

| Placeholder | Value |
|---|---|
| `{{to}}` | recipient in E.164 with `+`, e.g. `+919876543210` |
| `{{to_digits}}` | recipient without `+`, e.g. `919876543210` |
| `{{text}}` | message body |
| `{{<name>}}` | any value from the block's `variables` |

The `twilio` and `vonage` blocks are working examples. Vonage shows `{{to_digits}}` (it wants the
number without `+`), `unicode-fields` (`type=unicode` for non-GSM text), `success.each` (its HTTP 200
replies can still be failures) and `retry.codes` (`1` throttled, `5` internal error). The app checks
every block at startup and refuses to start with a clear list of problems if one is incomplete.
Design details: [docs/generic-http-provider-plan.md](docs/generic-http-provider-plan.md).

JSON has no comments, so this README is where the settings are explained. `providers.json` is read
through Spring's YAML loader (`classpath:providers.json[.yaml]` in `application.properties`), which
reads JSON as-is.

A provider that cannot be described this way (vendor SDK, JWT signing) can still be a Java class
that implements `MessageProvider` and is annotated `@Component`.

## Managing providers through the API

Providers can also be added while the app runs, with no file edits and no restart. They are kept
in `db.json` (path: `messaging.db-file`, default `./db.json`), which is created empty on first
start, read again at every start, and git-ignored because it holds real secrets.

| Request | Does | Success |
|---|---|---|
| `POST /api/v1/providers` | Register a provider | `201` with `Location` |
| `PUT /api/v1/providers/{id}` | Replace a registered provider | `200` |
| `DELETE /api/v1/providers/{id}` | Remove a registered provider | `204` |
| `GET /api/v1/providers` | List every provider, from both files | `200` |
| `GET /api/v1/providers/{id}` | One provider | `200` |

The body is the same block as in `providers.json`, plus `"enabled"` (missing means `false`), with
**real values** in `variables` (`${...}` placeholders are rejected):

```bash
curl -i -X POST http://localhost:8080/api/v1/providers \
  -H "Content-Type: application/json" \
  -d '{
    "id": "PLIVO",
    "display-name": "Plivo",
    "channels": ["SMS"],
    "enabled": true,
    "variables": { "auth-id": "<auth-id>", "auth-token": "<auth-token>", "from": "<plivo-number>" },
    "request": {
      "method": "POST",
      "url": "https://api.plivo.com/v1/Account/{{auth-id}}/Message/",
      "auth": { "type": "basic", "username": "{{auth-id}}", "password": "{{auth-token}}" },
      "body": { "format": "json", "fields": { "src": "{{from}}", "dst": "{{to_digits}}", "text": "{{text}}" } }
    },
    "response": { "id-path": "$.message_uuid[0]", "error": { "message-path": "$.error" } }
  }'
```

Rules:

- Every block gets the same checks as `providers.json`. A broken one returns `400` with a
  `problems` list naming every problem, and nothing is saved.
- An enabled provider needs a value for every variable.
- Responses mask every filled variable as `"****"`. In a `PUT`, a variable sent as `"****"`
  keeps its stored value, so secrets do not have to be sent again.
- Providers from `providers.json` (Twilio, Vonage) show `"read-only": true` and cannot be changed
  or removed through the API.
- Each change is checked, written to `db.json` (temp file, then rename), and only then used.
- Do not edit `db.json` by hand while the app runs: the next change overwrites it.
- These endpoints have no authentication yet. Add it before exposing the app.
