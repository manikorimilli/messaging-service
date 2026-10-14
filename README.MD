# Messaging Service

One HTTP endpoint that sends an SMS through Twilio or Vonage. The design is in
[docs/design.md](docs/design.md).

Java 21, Spring Boot 4.1.

## Configure

All settings are in `src/main/resources/application.properties`. Secrets come from environment
variables:

| Variable | Used for |
|---|---|
| `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` | Twilio credentials (Console, Account info) |
| `VONAGE_API_KEY`, `VONAGE_API_SECRET` | Vonage credentials (Dashboard, API settings) |
| `MESSAGING_PROVIDERS_TWILIO_SMSFROM` | Your Twilio phone number, e.g. `+15551234567` |
| `MESSAGING_PROVIDERS_VONAGE_SMSFROM` | Your Vonage number or sender name |

The sender values in `application.properties` are placeholders, so set the two `SMSFROM`
variables too.

The app checks the configuration at startup and refuses to start, with a clear message, when an
enabled provider has no credentials or the routing is wrong.

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
export TWILIO_AUTH_TOKEN=...
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

Add a folder under `provider/` with an adapter that implements `MessageProvider` and a
`@ConfigurationProperties` class, add its credentials and id to `application.properties`. No
other code changes. See section 6 of the design.
