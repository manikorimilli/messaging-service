# Messaging Service

One HTTP endpoint that sends an SMS through Twilio or Vonage. The design is in
[docs/design.md](docs/design.md).

Java 21, Spring Boot 4.1.

## Configure

- `src/main/resources/application.properties`: routing (which providers are enabled, which is
  the default) and HTTP timeouts.
- `src/main/resources/providers.json`: one block per provider describing how to call it (URL,
  login, body fields) and how to read its replies. See [Adding a provider](#adding-a-provider).

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

```properties
messaging.channels.sms.primary-provider=TWILIO
messaging.channels.sms.enabled-providers=TWILIO,VONAGE
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

Then add `EXAMPLE` to `messaging.channels.sms.enabled-providers` and its secrets to `.env`.

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
