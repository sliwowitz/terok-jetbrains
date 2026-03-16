# terok JetBrains Plugin

JetBrains IDE plugin for [terok](https://github.com/terok-ai/terok) — manage
containerized AI coding agents from your IDE.

**Status: PoC / Experimental**

## Features (PoC)

- **Task management** — view terok projects and tasks in the Services tool window
- **Agent workspace** — swap IDE content root to the agent's live workspace
- **Agent chat** — talk to in-container agents via ACP in JetBrains AI Chat
- **Git gate pull** — safely transfer agent changes to your local repo

## Requirements

- JetBrains IDE 2025.1+ (IntelliJ IDEA, PyCharm, CLion, GoLand, etc.)
- terok installed and running on the host
- Python 3 (for the ACP bridge script)

## Building

```bash
./gradlew build
```

## License

Apache-2.0
