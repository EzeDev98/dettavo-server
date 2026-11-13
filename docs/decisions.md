# Decisions

Where this file and the technical guide or build plan disagree, this file wins. It records the current decision only; reversed choices are not kept.

## Repositories and running

| Area | Decision |
|---|---|
| Repositories | Two independent repositories: `dettavo-server` (Spring Boot) and `dettavo-client` (Vite and React). Replaces the single `dettavo/` repository with `backend/` and `frontend/` (guide 3, plan 2a). Commands run from the root of the repository they name |
| Independence | The server never builds, serves, starts or refers to the client. They are linked only by configuration: the client's `VITE_API_BASE_URL` and the server's `app.security.allowed-origins` |
| Server address | `http://localhost:2060` (`server.port=${PORT:2060}`). Replaces 8080 everywhere in the guide and plan |
| Client address | `http://localhost:2061` for both `npm run dev` and `npm run preview`. Replaces 5173 everywhere. No Vite `/api` proxy |
| Host name | Always `localhost` in addresses (13 Nov 2026, replacing `127.0.0.1`). The app must be opened at `http://localhost:2061`, the one allowed origin. The server still listens on `127.0.0.1` only (`server.address`) |
| Pairing | None (13 Nov 2026): no pairing key, no `te_session` cookie, no `/session/pair` and no Pair screen, whatever the guide, the plan (3e, 4e, 20c) and prototype 5f say. The loopback binding, the Host check, the Origin check on writes and CORS protect the service. `/system/status` no longer reports `paired` |
| Running | Server: `./mvnw spring-boot:run` (development) or `./mvnw package` then `java -Xmx512m -jar target/dettavo.jar`. Client: `npm run dev`, or `npm run build` then `npm run preview`. No launcher scripts: plan 2f and guide 11.2 are dropped |
| Server build | Spring Boot 4.1.2 (guide said 4.1.1), Java 21, webmvc, validation, Lombok, springdoc 3.1.0; jar name `dettavo.jar`; base package `com.dettavo` |
| Client build | Ahorra's `package.json` scripts, `.nvmrc` (22), `jsconfig.json` aliases, ESLint and Stylelint configs, without PatternFly. `VITE_API_BASE_URL` in `.env` (git-ignored; `.env.example` committed), read only in `src/core/config/env.js` |

## Cross-origin rules (CORS)

The client calls the server across origins, as Ahorra does. Every later step that touches the network must follow this table.

| Where | Rule | Step |
|---|---|---|
| Server, `config/WebConfig` | `/api/**` allows only `app.security.allowed-origins` (default `http://localhost:2061`), no credentials; methods GET, POST, PUT, PATCH, DELETE, OPTIONS; request headers `Content-Type`, `If-Match`, `X-File-Name`, `Range`; exposed headers `ETag`, `Content-Range`, `Accept-Ranges`, `Content-Length`, `Content-Disposition`, `Location`; preflight cached 1 h. Add a header here whenever a new one is sent or read | 2d (done) |
| Server, `ApplicationProperties` | `app.security.allowed-origins` moves into `ApplicationProperties` with the other `app.*` values; the guide's "dev profile adds the 5173 origins" is replaced by this one property | 3a |
| Server, `LocalOnlyFilter` | The Origin check on writes uses the same allowed-origins list. `OPTIONS` preflight requests must pass the filter so CORS can answer them. Host check allows `127.0.0.1:2060` and `localhost:2060` | 3d |
| Client, `httpClient.js` | No credentials (there is no cookie); the base URL comes only from `env.js` | 4d |
| Client, `index.html` | Content-Security-Policy meta tag with `connect-src 'self' http://localhost:2060` and `media-src 'self' blob: http://localhost:2060` | 4d |
| Client, progress | `new EventSource(url)`, without credentials | 7e |
| Client, upload | `XMLHttpRequest` without credentials; `X-File-Name` triggers a preflight | 8a, 9b |
| Client, revisions | `If-Match` on save; the revision is read from the exposed `ETag` | 6d, 14f |
| Client, proxy video | `<video crossOrigin="anonymous">`; seeking depends on the exposed `Content-Range` and `Accept-Ranges` | 8g, 15a |
| Client, downloads | The file name comes from the exposed `Content-Disposition` | 17g, 18c |
| Client, PWA | Installs from `http://localhost:2061`; the service worker caches only the client's own files, never `/api` | 4f, 20 |

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
| Model | `ggml-small.en.bin` | 10 min of Ahorra Episode 3 audio transcribed in 158.8 s (3.8x realtime); clear text, only names and jargon wrong. On 12 Nov it was compared with `ggml-medium.en.bin` on a 15:25 recording (`~/Dettavo/spikes/test-1.wav`): small.en with a prompt took 272.1 s at 1,165 MB; medium.en took 807.8 s at 2,473 MB, above the 1.2 GB limit, and still heard "Ahorra" as "Ahura". medium.en is rejected and its model file deleted |
| Threads | 4 (`app.transcription.threads`) | 6 threads: 147.8 s but 821 s CPU against 649 s, 7% faster for 27% more CPU |
| Memory limit | 1.2 GB (guide said 1 GB) | Peaks: 1,133 MB (flash attention on), 1,230 MB (flash attention off), 1,282 MB (`--dtw`) |
| Default flags until Step 10b | `-l en -t 4 -ojf -pp`, flash attention on (whisper's default), no `--dtw` | Lightest option; see the postponed checks below |
| Prompt (check 1e, decided 13 Nov) | None. Whisper runs without `--prompt`; there is no "Words to expect" field and no `app.transcription.prompt`. A better model is the way to improve accuracy later | A per-project word list made the app more complicated than it is worth. Deleting a misheard word still cuts the right audio, because cuts use word times, not spelling |
| DTW constraint | `--dtw small.en` only works with `-nfa` (flash attention off); whisper silently disables it otherwise ("dtw_token_timestamps is not supported with flash_attn - disabling") | Seen in the Step 1 log. With `-nfa --dtw`: 176.7 s, 1,282 MB |
| Repetition loops | A loop guard is required (see 10b below) | On a noisy recording whisper repeated one phrase ("maybe it's not working") from about 2:30 to 10:00 with made-up timings |
| Known misheard words | Ahorra → "Ahura"; onboarding → "your body"; splash → "splice"/"supply"; Google Play → "go-go play"; handoff → "and off"; Vite → "Fighter"/"VITA"; Red Hat Text → "Red Hat test"; helper → "epimeter"; placeholders → "place orders"; theme → "time" | Ahorra Episode 3, first 10 minutes |

## Postponed Step 1 checks

These were postponed on 11 Nov 2026 because they tune configuration, not code structure. The step named in the first column must run its check before it is marked done. Spike files are in `~/Dettavo/spikes/`: `spike-10min.wav` (Ahorra Episode 3 audio), `spike-t4.json` (flash attention on), `spike-dtw.json` (`-nfa --dtw`), and `labels.py`, which turns whisper JSON into Audacity label files (`python3 ~/Dettavo/spikes/labels.py <json> plain|dtw <output-base>`).

| Run in | Check (plan ref) | How | Pass | Decides | Until then |
|---|---|---|---|---|---|
| 10b, before WhisperRunner's flags are final | Word edges and pauses (1d) | Make labels from `spike-t4.json` (plain) and `spike-dtw.json` (dtw); open `spike-10min.wav` in Audacity, import the word and pause labels, and check 20 word edges and 10 pauses | 18 of 20 edges within 100 ms; every pause of 0.5 s or more shows as at least 0.3 s | Whether to use `-nfa --dtw small.en`. If DTW does not clearly win, keep flash attention on and no DTW, saving about 150 MB | Flash attention on, no DTW |
| 10b | Repetition-loop guard (new) | Re-run the noisy recording (`video-1.mov`, which looped) once with `-mc 0` and once with `--vad -vm <silero model>`; re-run Ahorra audio with the winner | The loop is gone and the Ahorra transcript is no worse | Which guard WhisperRunner always passes. 10c should also drop a phrase repeated 3 or more times in a row as a last defence | Not guarded |
| 10e | Pause thresholds | Use the 1d pause checks to tune `app.transcription.*` (percentile, factor, hangover, limits) | Pauses checked in 1d come out at least as long as they really are | PauseRefiner defaults | Guide 6.4 defaults |
| 8e | Proxy and seeking (1h) | Build a 720p VideoToolbox proxy of 10 minutes of a real Ahorra screen recording; in Chrome, jump `currentTime` by 0.5 s every 3 s | Encode under 2.5 min; every jump lands with no visible stall over about 100 ms | Proxy encoder settings | Guide 5.5 command |
| Before 17 | Export sync with a hand-written graph (1f) | Hand-write `spike.filtergraph` with 8 keep segments and 10 ms fades over 10 minutes of a real Ahorra screen recording; export MP4 with VideoToolbox; `ffprobe` the result | Lips in sync to the end within one frame; no click at 10 joins; length matches the plan within 100 ms | Filter graph pattern (17b) | Guide 7.2 pattern |
| Before 17 | 200-segment export (1g) | Generate a 200-segment graph (keep 2.5 s, cut 0.5 s) and export again under `/usr/bin/time -l` | Peak under 1.5 GB and faster than realtime | If it fails, the per-segment fallback becomes the main export path (17f) | One-graph export |

Inputs still needed: a real Ahorra **screen recording** of at least 10 minutes with speech throughout, for 8e and the two export checks. Screen recordings are variable frame rate (seen: `r_frame_rate` 60, `avg_frame_rate` 59.96), so the export graph must add `fps=<avg_frame_rate>` before the trims (guide 7.2), and macOS recording names contain a hidden narrow space before "pm" (rename the file or use a wildcard).

## Prototype approvals (Build Plan Step 5)

`Dettavo.html` is the approved prototype for every screen. All of Step 5 was approved on 12 Nov 2026.

| Step | Screen | Decision |
|---|---|---|
| 5a | Home | One "Add video or audio for editing" button that also takes a file dropped anywhere, then the Projects grid. Rename in place: Enter or clicking away saves, Escape cancels, no Save button. Delete asks in a modal |
| 5b | Editor layout | Projects back button; Clips tray under the transcript with a dashed plus tile; a plus button in the toolbar; a file dropped anywhere in the editor lands in the tray; the timeline replaces the waveform; one joined transcript with each clip's name where it starts |
| 5c | Clip dragging | Drag a ready tile onto the timeline, drag clips along it to reorder, drag one back to the tray to take it off. An accent line shows where a clip will land. The transcript follows the timeline order. Each move is one undoable edit. Cuts are stored by word id, so they survive moving a clip or taking it off. Pause tokens and cuts never span two clips |
| 5d | Clip tile states | Uploading, Preparing and Transcribing with a percentage and a progress bar; "Waiting (n ahead)", because preparing and transcribing share one queue, one clip at a time; Failed with Retry; Upload stopped with Try again; "No speech found", which can still go on the timeline; "Not on timeline". The trash icon (on hover or focus) uses the projects' Delete modal; deleting a clip takes it out of the tray, the queue and the timeline and cannot be undone |
| 5e | Editor before a clip is ready | Files added from Home make a new project; files added in the editor join the open one. Nothing goes on the timeline until a ready clip is dragged there. Until then the transcript pane shows "Getting your clip ready" with a row per clip (state, percentage, progress bar), the video pane says "Nothing to play yet", the transport and Export are off, the ruler is hidden and the timeline shows a drag hint |
| 5f | Install flow | Onboarding, Install, hand-off, Setup and Service down (no Pair: see Pairing), on Ahorra's screen pattern. Full-width buttons are 42 px, Ahorra's block-button height. The install dialog is Chrome's own, drawn by the browser: on the Mac it is centred under the toolbar ("Install app", Learn More, Cancel, Next) |

## Transcription (Build Plan Step 10)

| Area | Decision |
|---|---|
| Prompt | None (decision 1e): no `wordsToExpect` in project.json and no `app.transcription.prompt`, whatever plan 10g and guide 6.2 say. WhisperRunner never passes `--prompt` |
| Checks 1d and 1j | Settings, not code: `app.transcription.dtw` (adds `-nfa --dtw <model>`) and `app.transcription.loop-guard` (`none`, `max-context-zero` for `-mc 0`, or `vad` with `app.transcription.vad-model`). Defaults: no DTW, no guard, until the checks are done. `video-1.mov` and the old `labels.py` no longer exist: 1j runs on `test-1.wav` against `test-1-small-prompt.txt`, and `labels.py` was rewritten on 12 Nov |
| Repetition filter (10c) | A phrase of 2 to 12 words repeated 3 or more times in a row keeps its first copy. Single words are never dropped ("no, no, no" is real speech) |
| Non-speech (10c) | Bracketed spans such as `[BLANK_AUDIO]`, `(laughs)` and `*music*` are dropped, even when split across tokens, and so are words with no letter or digit |
| Chunk files | Each finished chunk's JSON stays in `cache/whisper/` for resuming; chunk WAVs are deleted once whisper has read them. Rebuilding the cache deletes both; the transcript is in project.json |
| Cancel and restart | A cancelled transcription returns the clip to PREPARED; a failed one leaves it FAILED, with Retry (`POST .../transcribe`). On startup, clips left TRANSCRIBING, or PREPARED with no transcript, are queued again and resume from their finished chunks, unless their last transcription was cancelled |
| ETA | Measured from the first chunk actually transcribed in this run, so reused chunks do not make it too low |
| Pause refinement | Guide 6.4 defaults as `app.transcription.*`: 20th percentile, x3, 20 ms hangover and lead-in, 300 ms limit. A floor of about -60 dBFS stops digital silence from making every frame loud |

## Transcription (Build Plan Step 10)

| Area | Decision |
|---|---|
| Prompt | None (decision 1e): no `wordsToExpect` in project.json and no `app.transcription.prompt`, whatever plan 10g and guide 6.2 say. WhisperRunner never passes `--prompt` |
| Checks 1d and 1j | Settings, not code: `app.transcription.dtw` (adds `-nfa --dtw <model>`) and `app.transcription.loop-guard` (`none`, `max-context-zero` for `-mc 0`, or `vad` with `app.transcription.vad-model`). Defaults: no DTW, no guard, until the checks are done. `video-1.mov` and the old `labels.py` no longer exist: 1j runs on `test-1.wav` against `test-1-small-prompt.txt`, and `labels.py` was rewritten on 12 Nov |
| Repetition filter (10c) | A phrase of 2 to 12 words repeated 3 or more times in a row keeps its first copy. Single words are never dropped ("no, no, no" is real speech) |
| Non-speech (10c) | Bracketed spans such as `[BLANK_AUDIO]`, `(laughs)` and `*music*` are dropped, even when split across tokens, and so are words with no letter or digit |
| Chunk files | Each finished chunk's JSON stays in `cache/whisper/` for resuming; chunk WAVs are deleted once whisper has read them. Rebuilding the cache deletes both; the transcript is in project.json |
| Cancel and restart | A cancelled transcription returns the clip to PREPARED; a failed one leaves it FAILED, with Retry (`POST .../transcribe`). On startup, clips left TRANSCRIBING, or PREPARED with no transcript, are queued again and resume from their finished chunks, unless their last transcription was cancelled |
| ETA | Measured from the first chunk actually transcribed in this run, so reused chunks do not make it too low |
| Pause refinement | Guide 6.4 defaults as `app.transcription.*`: 20th percentile, x3, 20 ms hangover and lead-in, 300 ms limit. A floor of about -60 dBFS stops digital silence from making every frame loud |

## Export (Build Plan Step 17)

| Area | Decision |
|---|---|
| Graph and memory | Built on the guide 7.2 one-graph pattern. Checks 1f and 1g have not been run yet; 17 is not done until they pass on a real screen recording. 17f (per-segment fallback) is built only if 1g fails |
| Audio rate | Every export's audio is 48 kHz stereo (7.1 rule 2), so WAV is 48 kHz too, not the source rate 7.3 mentions |
| Frame snapping | Video exports snap keep segments to the first video clip's average frame rate; MP3 and WAV keep the planner's milliseconds |
| MP4 without video | 400 "An MP4 export needs a video clip on the timeline"; the dialog hides MP4 in that case (18a) |
| Finished file name | The job does not carry the file name; the dialog reads the newest entry of GET /exports after the job succeeds |
| Development endpoints | GET /projects/{id}/export-plan (and /filtergraph) and POST /dev/projects/{id}/export-broken, dev profile only |

## Install flow (Build Plan Step 20)

| Area | Decision |
|---|---|
| App icon | The owner's Dettavo logo (the D with the orange cut, 13 Nov 2026), kept exactly as supplied in dettavo-client `assets/icon-source.png`. `npm run icons` (sharp, from Ahorra's script) only scales it: the maskable and Apple icons place it on #1e1e1e, the others keep its transparent surround |
| Routes | Onboarding `/welcome` and Install `/install`, in a browser tab only. Every other route needs the installed app (InstallGuard) and sends a tab to `/welcome` |
| First-run order | Service down, then Setup while a tool is missing, then Home (plan 20c without Pair) |
| Install steps | Chrome on the Mac only, as in the prototype. Ahorra's other platforms are left out. The steps show when Chrome cancels its dialog or offers none within 10 seconds |
| Service down | No "Try again" button (prototype 5f): the status query asks every 3 seconds |
| Development | `VITE_SKIP_INSTALL_GUARD=true` in `.env` lets `npm run dev` run the whole app in a tab. A build ignores it |
| Updates | Ahorra's UpdatePrompt, already in place and unchanged: a new build is offered within 3 minutes, or when the app comes back to the front |
EOFcat >> docs/decisions.md <<'EOF'

## Colour

| Area | Decision |
|---|---|
| Accent (13 Nov 2026) | Electric lavender `#c4a1ff` replaces the prototype's blue everywhere: primary buttons, focus, selection, the current word, progress and the timeline's clip blocks. The app icon is the owner's logo, not recoloured. Primary buttons carry dark text (`#1e1e1e`), since white on lavender is too faint. Everything else in the prototype stays as drawn |
