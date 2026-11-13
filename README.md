# dettavo-server

The local service behind Dettavo, a transcript-based audio and video editor that runs entirely on this Mac. It is a Spring Boot application on `http://localhost:2060`. It transcribes with whisper.cpp, edits and exports with FFmpeg, and keeps every project in `~/Dettavo`.

The app itself is [dettavo-client](../dettavo-client), installed from `http://localhost:2061`. Decisions that change the technical guide are in [docs/decisions.md](docs/decisions.md).

## Requirements

- macOS (Apple silicon or Intel)
- Java 21 (Temurin 21) as the `java` on your PATH
- Node 22, for dettavo-client
- git and python3 (from the Xcode command line tools: `xcode-select --install`)
- About 1 GB free for the tools and the speech model, plus room for your projects

## Install the tools

Run this once, in this folder:

```bash
./scripts/install-tools.sh
```

It is safe to run again; anything already in place is skipped.
- Creates the workspace `~/Dettavo`.
- Keeps an existing FFmpeg 7 or newer that has the `h264_videotoolbox`, `libx264` and `libmp3lame` encoders. Homebrew is only a fallback, and on Intel Macs it compiles from source.
- Builds `whisper-cli` 1.9.5 from source in `~/Dettavo/tools/whisper.cpp` and links it into `/usr/local/bin`. CMake is installed with pip if it is missing.
- Downloads the speech model `ggml-small.en.bin` (about 466 MB) to `~/Dettavo/models`.
- Reports on Java and Node, but never installs them.

These environment variables change where things go:

| Variable | Default |
|---|---|
| `APP_WORKSPACE_DIR` | `~/Dettavo` |
| `WHISPER_MODEL_PATH` | `~/Dettavo/models/ggml-small.en.bin` |
| `WHISPER_CPP_REF` | `v1.9.5` |
| `TOOLS_LINK_DIR` | `/usr/local/bin` (must be writable and on your PATH) |

If a tool is still missing, the app opens on its Setup screen with the same command.

## Run

While developing:

```bash
./mvnw spring-boot:run
```

Add `-Dspring-boot.run.profiles=dev` for Swagger UI at http://localhost:2060/swagger-ui.html, the development endpoints and debug logs.

The built app:

```bash
./mvnw package
java -Xmx512m -jar target/dettavo.jar
```

The server listens on 127.0.0.1 only, so nothing outside this Mac can reach it. Leave it running while you use Dettavo. If it stops, the app shows "Dettavo's local service isn't running" and recovers on its own once the server is back.

## Security

There is no pairing or key. The service listens on 127.0.0.1 only, so nothing outside this Mac can reach it. It answers only requests addressed to `localhost:2060` or `127.0.0.1:2060`, which blocks DNS-rebinding tricks. It accepts changes (POST, PUT, PATCH, DELETE) only from the app at `http://localhost:2061`, and CORS keeps every other website from reading its answers.

## Configuration

Every setting is in `src/main/resources/application.properties`. Override the common ones with environment variables:

| Variable | Setting | Default |
|---|---|---|
| `PORT` | `server.port` | `2060` |
| `APP_WORKSPACE_DIR` | `app.workspace-dir` | `~/Dettavo` |
| `FFMPEG_PATH`, `FFPROBE_PATH`, `WHISPER_CLI_PATH` | `app.tools.*` | found in `/opt/homebrew/bin`, `/usr/local/bin`, `/usr/bin` |
| `WHISPER_MODEL_PATH` | `app.tools.whisper-model` | `~/Dettavo/models/ggml-small.en.bin` |
| `WHISPER_THREADS` | `app.transcription.threads` | `4` |
| `ALLOWED_ORIGINS` | `app.security.allowed-origins` | `http://localhost:2061` |

You can change any other `app.*` setting in two ways:
- **Machine-specific overrides.** Put them in `src/main/resources/application-local.properties`, which is git-ignored, and run with the `local` profile: `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`, or `java -Xmx512m -jar target/dettavo.jar --spring.profiles.active=local`.
- **One run only.** Pass the setting on the command line, for example `java -Xmx512m -jar target/dettavo.jar --app.transcription.threads=8`.

If you change the port or the client's address, change dettavo-client's `.env` to match. Always open the app at `http://localhost:2061`: the service accepts changes only from the addresses in `ALLOWED_ORIGINS`.

## Where files live

```
~/Dettavo/
  models/ggml-small.en.bin    the speech model
  tools/whisper.cpp/          whisper.cpp source and build
  logs/                       logs from development tool checks
  projects/<projectId>/
    project.json              the project: clips, timeline, edits
    clips/<clipId>/source/    each uploaded file, exactly as it was added
    clips/<clipId>/cache/     proxy, waveform peaks, transcription audio and results
    exports/                  finished exports
    logs/<jobId>.log          one log per transcription or export
```

Your original files are never changed. Deleting a project removes its folder.
