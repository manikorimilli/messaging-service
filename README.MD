# Messaging Service

## Run the Messaging Service on your computer

The Messaging Service sends SMS through Twilio and Vonage using one REST API. Follow these steps
to run it locally. It takes about 15 minutes the first time.

## What you need

- **Java 21 or newer (JDK).** Install commands are in step 1.
- **Git.** To download the code. Install commands are in step 1.
- **The `.env` values.** The Twilio and Vonage keys. You receive these separately, not in this
  guide.

You don't need to install Maven. The project includes its own Maven (`mvnw`), which downloads
itself on first use.

## Steps

### 1. Install Java and Git

Skip this step if `java -version` already shows 21 or higher and `git --version` works. Use the
commands for your system.

**macOS (with Homebrew)**

If you don't have Homebrew yet, install it first:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Then install Java 21 and Git:

```bash
brew install --cask temurin@21
brew install git
```

**Linux**

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk git
```

**Windows (PowerShell or Command Prompt)**

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK -e
winget install --id Git.Git -e
```

Close the terminal and open a new one after installing, so it finds Java and Git.

**Check (all systems)**

```bash
java -version
git --version
```

`java -version` must show version 21 or higher, for example `openjdk version "21.0.5"`.

### 2. Download the code

```bash
git clone https://github.com/manikorimilli/messaging-service.git
cd messaging-service
```

Run every command below from this `messaging-service` folder.

### 3. Create your settings file

Copy the example file to a new file named `.env` in the same folder.

**macOS / Linux**

```bash
cp .env.example .env
```

**Windows**

```powershell
copy .env.example .env
```

`.env` holds your secret keys. It is ignored by Git. Never commit it or share it.

### 4. Paste in the values you received

The values for `.env` are sent to you separately. Open `.env` in any editor. It has every setting
from `.env.example`, each with an empty value. Fill in every setting with the value you received,
exactly as you received it. Every setting must have a value, or the app refuses to start.

- Put each value right after its `=` sign, with no spaces and no quotes.
- Keep the setting names exactly as they are. Save the file when you are done.
- If you received a complete `.env` file, you can use it instead: put it in the
  `messaging-service` folder in place of the one from step 3.

### 5. Start the app

**macOS / Linux**

```bash
./mvnw spring-boot:run
```

**Windows**

```powershell
.\mvnw.cmd spring-boot:run
```

The first start downloads the libraries and can take a few minutes. The app is ready when the log
shows a line like:

```
Started MessagingServiceApplication in 3.5 seconds
```

It runs at `http://localhost:8080`. Keep this terminal open. To stop the app, press `Ctrl + C`
in it.

### 6. Check that it works

Open this address in your browser. You should see `"status":"UP"`.

```
http://localhost:8080/actuator/health
```

Then open the API documentation, where you can see and try every endpoint:

```
http://localhost:8080/swagger-ui.html
```

### 7. Send a test SMS

Open a second terminal and run this, with your own phone number in international format in place
of `+91XXXXXXXXXX`.

**macOS / Linux**

```bash
curl -i -X POST http://localhost:8080/api/v1/messages \
  -H "Content-Type: application/json" \
  -d '{ "type": "SMS", "to": "+91XXXXXXXXXX", "body": "sms_internal_alerts" }'
```

**Windows:** use Swagger instead. Open `http://localhost:8080/swagger-ui.html`, choose
`POST /api/v1/messages`, click **Try it out**, paste the body below with your number, and click
**Execute**.

**Request body**

```json
{ "type": "SMS", "to": "+91XXXXXXXXXX", "body": "sms_internal_alerts" }
```

`202 Accepted` means the provider took the message.

> **Trial accounts:** a Twilio trial only sends to numbers verified in your Twilio Console (others
> fail with error `21608`). Vonage demo mode only sends to whitelisted test numbers (others fail
> with status `29`).

## If something goes wrong

| You see | Fix |
|---|---|
| `TWILIO is enabled for SMS but its credentials are missing` | `.env` is missing, is not in the project folder, or has an empty value. Fill in every setting with the values you received (step 4). |
| `Port 8080 was already in use` | Another program uses port 8080. Stop that program, then start the app again. |
| `java: command not found`, or an error about release 21 | Install Java (step 1) and open a new terminal. `java -version` must show 21 or higher. |
| `JAVA_HOME is not defined correctly` (Windows) | Open a new terminal after installing Java. If it still appears, set `JAVA_HOME` to the Java folder, for example `C:\Program Files\Eclipse Adoptium\jdk-21...`. |
| `permission denied: ./mvnw` | Run `chmod +x mvnw` once. |
| `502 PROVIDER_REJECTED` with `Authentication Error` | A value in `.env` was not copied correctly. Check it against the values you received, with no extra spaces. |
| Error `21608` or status `29` | Trial or demo account: verify or whitelist the phone number with the provider. |
