# Decisions

Where this file and the technical guide or build plan disagree, this file wins. It records the current decision only; reversed choices are not kept.

## Repositories and running

| Area | Decision |
|---|---|
| Repositories | Two independent repositories: `dettavo-server` (Spring Boot) and `dettavo-client` (Vite and React). Replaces the single `dettavo/` repository with `backend/` and `frontend/` (guide 3, plan 2a). Commands run from the root of the repository they name |
| Independence | The server never builds, serves, starts or refers to the client. They are linked only by configuration: the client's `VITE_API_BASE_URL` and the server's `app.security.allowed-origins` |
| Server address | `http://127.0.0.1:2060` (`server.port=${PORT:2060}`). Replaces 8080 everywhere in the guide and plan |
| Client address | `http://127.0.0.1:2061` for both `npm run dev` and `npm run preview`. Replaces 5173 everywhere. No Vite `/api` proxy |
| Host name | Always `127.0.0.1`, never `localhost`: `127.0.0.1:2061` and `127.0.0.1:2060` are the same site, so the pairing cookie is sent |
| Running | Server: `./mvnw spring-boot:run` (development) or `./mvnw package` then `java -Xmx512m -jar target/dettavo.jar`. Client: `npm run dev`, or `npm run build` then `npm run preview`. No launcher scripts: plan 2f and guide 11.2 are dropped |
| Server build | Spring Boot 4.1.2 (guide said 4.1.1), Java 21, webmvc, validation, Lombok, springdoc 3.1.0; jar name `dettavo.jar`; base package `com.dettavo` |
| Client build | Ahorra's `package.json` scripts, `.nvmrc` (22), `jsconfig.json` aliases, ESLint and Stylelint configs, without PatternFly. `VITE_API_BASE_URL` in `.env` (git-ignored; `.env.example` committed), read only in `src/core/config/env.js` |

## Cross-origin rules (CORS)

The client calls the server across origins, as Ahorra does. Every later step that touches the network must follow this table.

| Where | Rule | Step |
|---|---|---|
| Server, `config/WebConfig` | `/api/**` allows only `app.security.allowed-origins` (default `http://127.0.0.1:2061`), credentials on; methods GET, POST, PUT, PATCH, DELETE, OPTIONS; request headers `Content-Type`, `If-Match`, `X-File-Name`, `Range`; exposed headers `ETag`, `Content-Range`, `Accept-Ranges`, `Content-Length`, `Content-Disposition`, `Location`; preflight cached 1 h. Add a header here whenever a new one is sent or read | 2d (done) |
| Server, `ApplicationProperties` | `app.security.allowed-origins` moves into `ApplicationProperties` with the other `app.*` values; the guide's "dev profile adds the 5173 origins" is replaced by this one property | 3a |
| Server, `LocalOnlyFilter` | The Origin check on writes uses the same allowed-origins list. `OPTIONS` preflight requests must pass the filter so CORS can answer them. Host check allows `127.0.0.1:2060` and `localhost:2060` | 3d |
| Server, pairing cookie | `te_session`: `HttpOnly`, `SameSite=Strict`, `Path=/`, not `Secure` (plain http on loopback) | 3e |
| Pairing | No `start.sh --pair`. The server logs the pairing key at startup; the user opens `http://127.0.0.1:2061/pair#<key>` | 3e, 4e |
| Client, `httpClient.js` | Every request sends `credentials: 'include'`; the base URL comes only from `env.js` | 4d |
| Client, `index.html` | Content-Security-Policy meta tag with `connect-src 'self' http://127.0.0.1:2060` and `media-src 'self' blob: http://127.0.0.1:2060` | 4d |
| Client, progress | `new EventSource(url, { withCredentials: true })` | 7e |
| Client, upload | `XMLHttpRequest` with `withCredentials = true`; `X-File-Name` triggers a preflight | 8a, 9b |
| Client, revisions | `If-Match` on save; the revision is read from the exposed `ETag` | 6d, 14f |
| Client, proxy video | `<video crossOrigin="use-credentials">`; seeking depends on the exposed `Content-Range` and `Accept-Ranges` | 8g, 15a |
| Client, downloads | The file name comes from the exposed `Content-Disposition` | 17g, 18c |
| Client, PWA | Installs from `http://127.0.0.1:2061`; the service worker caches only the client's own files, never `/api` | 4f, 20 |

## Tools on the MacBook (2018 MacBook Pro, Intel, zsh)

| Tool | Decision |
|---|---|
| Homebrew | No longer builds bottles for Intel macOS; installing through it compiles from source. Avoid `brew install` and `brew upgrade` for these tools |
| FFmpeg | 7.1.1, pinned (`brew pin ffmpeg`). Has `h264_videotoolbox`, `libx264`, `libmp3lame`. 7.0 or newer, so the export graph flag is `-/filter_complex` |
| whisper.cpp | Built from source at `~/Dettavo/tools/whisper.cpp` (1.9.5 line), static `whisper-cli` with `GGML_METAL=OFF`, linked into `/usr/local/bin`. No OpenMP; uses plain threads. CMake installed with `python3 -m pip install --user cmake` |
| Model | `~/Dettavo/models/ggml-small.en.bin` (465 MB) |
| Java, Node | Managed by the owner: Java 21 (Temurin 21.0.3), Node 22. Never installed by a script |
| `scripts/install-tools.sh` | In `dettavo-server`. Keeps an existing FFmpeg 7+ with the three encoders (Homebrew only as a fallback), builds `whisper-cli` from source, downloads the model through a `.partial` file with a size check, reports on Java and Node. Starts nothing |

## Transcription settings from Step 1

| Setting | Value | Evidence |
|---|---|---|
| Model | `ggml-small.en.bin` | 10 min of Ahorra Episode 3 audio transcribed in 158.8 s (3.8x realtime); clear text, only names and jargon wrong |
| Threads | 4 (`app.transcription.threads`) | 6 threads: 147.8 s but 821 s CPU against 649 s, 7% faster for 27% more CPU |
| Memory limit | 1.2 GB (guide said 1 GB) | Peaks: 1,133 MB (flash attention on), 1,230 MB (flash attention off), 1,282 MB (`--dtw`) |
| Default flags until Step 10b | `-l en -t 4 -ojf -pp`, flash attention on (whisper's default), no `--dtw` | Lightest option; see the postponed checks below |
| DTW constraint | `--dtw small.en` only works with `-nfa` (flash attention off); whisper silently disables it otherwise ("dtw_token_timestamps is not supported with flash_attn - disabling") | Seen in the Step 1 log. With `-nfa --dtw`: 176.7 s, 1,282 MB |
| Repetition loops | A loop guard is required (see 10b below) | On a noisy recording whisper repeated one phrase ("maybe it's not working") from about 2:30 to 10:00 with made-up timings |
| Known misheard words | Ahorra → "Ahura"; onboarding → "your body"; splash → "splice"/"supply"; Google Play → "go-go play"; handoff → "and off"; Vite → "Fighter"/"VITA"; Red Hat Text → "Red Hat test"; helper → "epimeter"; placeholders → "place orders"; theme → "time" | Ahorra Episode 3, first 10 minutes |

## Postponed Step 1 checks

These were postponed on 11 Nov 2026 because they tune configuration, not code structure. The step named in the first column must run its check before it is marked done. Spike files are in `~/Dettavo/spikes/`: `spike-10min.wav` (Ahorra Episode 3 audio), `spike-t4.json` (flash attention on), `spike-dtw.json` (`-nfa --dtw`), and `labels.py`, which turns whisper JSON into Audacity label files (`python3 ~/Dettavo/spikes/labels.py <json> plain|dtw <output-base>`).

| Run in | Check (plan ref) | How | Pass | Decides | Until then |
|---|---|---|---|---|---|
| 10b, before WhisperRunner's flags are final | Word edges and pauses (1d) | Make labels from `spike-t4.json` (plain) and `spike-dtw.json` (dtw); open `spike-10min.wav` in Audacity, import the word and pause labels, and check 20 word edges and 10 pauses | 18 of 20 edges within 100 ms; every pause of 0.5 s or more shows as at least 0.3 s | Whether to use `-nfa --dtw small.en`. If DTW does not clearly win, keep flash attention on and no DTW, saving about 150 MB | Flash attention on, no DTW |
| 10b | Names with `--prompt` (1e) | Re-run the chosen setting with `--prompt "Ahorra, Vite, PWA, onboarding, splash screen, Google Play, Red Hat Text, handoff, helper, placeholders, Spring Boot, React"` | The names in "Known misheard words" are spelled correctly; list any that are not | The 11a "Words to expect" default text. Note: with `-mc 0` the prompt only reaches the first 30 s window unless `--carry-initial-prompt` is added | No prompt |
| 10b | Repetition-loop guard (new) | Re-run the noisy recording (`video-1.mov`, which looped) once with `-mc 0` and once with `--vad -vm <silero model>`; re-run Ahorra audio with the winner | The loop is gone and the Ahorra transcript is no worse | Which guard WhisperRunner always passes. 10c should also drop a phrase repeated 3 or more times in a row as a last defence | Not guarded |
| 10e | Pause thresholds | Use the 1d pause checks to tune `app.transcription.*` (percentile, factor, hangover, limits) | Pauses checked in 1d come out at least as long as they really are | PauseRefiner defaults | Guide 6.4 defaults |
| 8e | Proxy and seeking (1h) | Build a 720p VideoToolbox proxy of 10 minutes of a real Ahorra screen recording; in Chrome, jump `currentTime` by 0.5 s every 3 s | Encode under 2.5 min; every jump lands with no visible stall over about 100 ms | Proxy encoder settings | Guide 5.5 command |
| Before 17 | Export sync with a hand-written graph (1f) | Hand-write `spike.filtergraph` with 8 keep segments and 10 ms fades over 10 minutes of a real Ahorra screen recording; export MP4 with VideoToolbox; `ffprobe` the result | Lips in sync to the end within one frame; no click at 10 joins; length matches the plan within 100 ms | Filter graph pattern (17b) | Guide 7.2 pattern |
| Before 17 | 200-segment export (1g) | Generate a 200-segment graph (keep 2.5 s, cut 0.5 s) and export again under `/usr/bin/time -l` | Peak under 1.5 GB and faster than realtime | If it fails, the per-segment fallback becomes the main export path (17f) | One-graph export |

Inputs still needed: a real Ahorra **screen recording** of at least 10 minutes with speech throughout, for 8e and the two export checks. Screen recordings are variable frame rate (seen: `r_frame_rate` 60, `avg_frame_rate` 59.96), so the export graph must add `fps=<avg_frame_rate>` before the trims (guide 7.2), and macOS recording names contain a hidden narrow space before "pm" (rename the file or use a wildcard).
