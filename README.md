# xUmbra

Android client for **Umbra**, my self-hosted personal agent running on a Rock Pi 4B. A chat
front-end, an operations dashboard and a small pixel-art world in one Compose app.

> The server side lives in a separate repository: `umbra-server` (FastAPI + Telegram).

## Features

- **Chat with the agent** — streaming responses, tool-call cards, markdown rendering,
  image attachments, model picker and per-chat settings.
- **Agents → Rooms** — every background service on the server gets a pixel room. The
  character sleeps while idle, sits at the desk when a run starts, shows ✅/❌ when it ends
  and turns red with ⚠ when the agent is unhealthy.
- **Memory and notes** — browse the agent's long-term memory, notes and usage.
- **Personas** — switch the agent's persona and voice.
- **Lab** — image tools, sprites, stories, Japanese lookup and other server-side toys.
- **Terminal** — a small ANSI emulator for the server's shell endpoints.
- **Vault** — protected local storage for notes and media.
- **Pixel-art themes** — every screen is themed with hand-drawn sprites and decorations.

## Tech

Kotlin · Jetpack Compose · OkHttp · Coil · a foreground hub service · `minSdk 29`,
`targetSdk 35`

No external SDKs: the client talks to the Umbra HTTP/WebSocket API directly.

## Architecture

```
app/src/main/java/com/maiconerick/xumbra/
├── data/      Hub API client, models, preferences, notifications, images
├── service/   HubService (connection + event stream)
└── ui/        Chat, Rooms, Agents, Memory, Personas, Lab, Terminal, Vault, themes
```

## Configuration

The app expects an Umbra hub URL and token, entered in Settings on first run. Nothing is
hardcoded.

## Build

Requirements: JDK 17, Android SDK 36.

```bash
# local.properties
sdk.dir=/path/to/Android/Sdk

./gradlew assembleDebug
```

## License

MIT — see [LICENSE](LICENSE).
