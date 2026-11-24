# Dettavo

Dettavo is a transcript-based audio and video editor that runs entirely on the owner's Mac. You add recordings, Dettavo transcribes them locally, and you edit by editing the text: delete words, shorten pauses, cut silences, split clips and arrange them on a timeline. The edited result is exported as MP4, MP3 or WAV. Nothing leaves the machine.

This file records the current design decisions only.

## Architecture

| Area | Decision |
|---|---|
| Repositories | Two independent repositories: `dettavo-server` (Spring Boot) and `dettavo-client` (Vite and React). Commands run from the root of the repository they name |
| Independence | The server never builds, serves, starts or refers to the client. They are linked only by configuration: the client's `VITE_API_BASE_URL` and the server's `app.security.allowed-origins` |
| Server | Spring Boot 4.1.2, Java 21, webmvc, validation, Lombok, springdoc 3.1.0, virtual threads. Base package `com.dettavo`, organised by feature (`edit`, `export`, `job`, `media`, `project`, `system`, `transcription`), each split into `dto`, `enums`, `service`, `web` and `event` as needed. Jar name `dettavo.jar` |
| Client | Vite, React and TanStack Query, Node 22. `VITE_API_BASE_URL` lives in `.env` (git-ignored; `.env.example` committed) and is read only in `src/core/config/env.js` |
| Addresses | Server `http://localhost:2060` (`server.port=${PORT:2060}`), client `http://localhost:2061` for both `npm run dev` and `npm run preview`. Addresses always use `localhost`. No Vite `/api` proxy |
| Running | Server: `./mvnw spring-boot:run`, or `./mvnw package` then `java -Xmx512m -jar target/dettavo.jar`. Client: `npm run dev`, or `npm run build` then `npm run preview`. No launcher scripts |
| API docs | Swagger UI and the OpenAPI document are on only under the `dev` profile, as are the development endpoints |

## Security

| Area | Decision |
|---|---|
| Local only | The server listens on `127.0.0.1` only |
| No pairing | There is no pairing key, session cookie or Pair screen. The loopback binding, the Host check, the Origin check on writes and CORS protect the service |
| `LocalOnlyFilter` | The Host header must be `127.0.0.1:2060` or `localhost:2060` (blocks DNS rebinding). POST, PUT, PATCH and DELETE must come from an allowed origin. `OPTIONS` preflights pass after the Host check so CORS can answer them. Every response carries `nosniff` and `no-referrer` |
| Processes | Every child process is started through one runner with an argument list, never a shell string, with an explicit environment, continuous output draining, cancellation, timeouts and a per-job log file |
| Paths | Every path is resolved inside the workspace; file names from the browser are sanitised before they touch the disk |

## Cross-origin rules (CORS)

The client calls the server across origins. Anything that touches the network follows this table.

| Where | Rule |
|---|---|
| Server, `WebConfig` | `/api/**` allows only `app.security.allowed-origins` (default `http://localhost:2061`), no credentials; methods GET, POST, PUT, PATCH, DELETE, OPTIONS; request headers `Content-Type`, `If-Match`, `X-File-Name`, `Range`; exposed headers `ETag`, `Content-Range`, `Accept-Ranges`, `Content-Length`, `Content-Disposition`, `Location`; preflight cached 1 h. A new header that is sent or read is added here |
| Server, errors | Errors are ProblemDetail bodies with a `code`. Errors raised in a filter carry the CORS headers too, so the client can read the status instead of seeing a network error |
| Client, `httpClient.js` | No credentials; the base URL comes only from `env.js` |
| Client, `index.html` | Content-Security-Policy with `connect-src 'self' http://localhost:2060` and `media-src 'self' blob: http://localhost:2060` |
| Client, progress | `new EventSource(url)`, without credentials |
| Client, upload | `XMLHttpRequest` without credentials; `X-File-Name` triggers a preflight |
| Client, revisions | `If-Match` on save; the revision is read from the exposed `ETag` |
| Client, proxy video | `<video crossOrigin="anonymous">`; seeking depends on the exposed `Content-Range` and `Accept-Ranges` |
| Client, downloads | The file name comes from the exposed `Content-Disposition` |
| Client, PWA | Installs from `http://localhost:2061`; the service worker caches only the client's own files, never `/api` |

## Workspace and storage

| Area | Decision |
|---|---|
| Workspace | `~/Dettavo` (`APP_WORKSPACE_DIR`), with `projects/`, `models/` and `tools/` |
| Project | `projects/<uuid>/project.json` holds clips, transcripts, edits and the last 20 job summaries. Times in it are kept to the second |
| Clip folder | `clips/<clipId>/source/<sanitised-name>` is never modified; `cache/` holds everything that can be rebuilt (16 kHz audio, proxy, thumbnail, levels, filmstrip, whisper chunks). Clip ids are `c01`, `c02` and upward and are never reused |
| Writes | Files are written to `.tmp` or `.partial` and renamed only when complete. Leftover `.tmp`, `.partial` and `.filtergraph` files are deleted on startup |
| Locking | Each project is loaded, changed and saved under its own lock; nothing is written if a change fails |
| Disk space | A clip is registered before its upload so the space can be checked first; 2 GB is always kept free (`app.disk.reserve-bytes`). A 413 carries `requiredBytes` and `availableBytes` |

## Tools

| Tool | Decision |
|---|---|
| FFmpeg | 7.0 or newer with `h264_videotoolbox`, `libx264` and `libmp3lame`. On FFmpeg 7 the export graph is read with `-/filter_complex`, otherwise `-filter_complex_script` |
| whisper.cpp | `whisper-cli` 1.9.5 built from source in `~/Dettavo/tools/whisper.cpp`, linked into `/usr/local/bin` |
| Model | `~/Dettavo/models/ggml-small.en.bin`. `medium.en` is rejected: about three times slower and above the memory limit, with no real gain in accuracy |
| Homebrew | Avoided on Intel Macs, where it compiles from source. An existing FFmpeg 7+ is kept; Homebrew is only a fallback |
| Java, Node | Java 21 and Node 22 are managed by the owner and never installed by a script |
| `scripts/install-tools.sh` | Installs FFmpeg if needed, builds `whisper-cli`, downloads the model through a `.partial` file with a size check and reports on Java and Node. Safe to run again. Starts nothing |
| Detection | The server finds the tools at startup and on request. A missing tool never stops the server; `/system/status` lists it with the command that fixes it |

## Media pipeline

| Area | Decision |
|---|---|
| Upload | The raw request body is streamed to `source/<name>.partial` through a 1 MB buffer, counted against `Content-Length`, renamed and probed. Multipart is off. The name arrives in `X-File-Name`, URI-encoded. A stopped upload deletes the `.partial` file and the clip shows "Upload stopped" with Try again |
| Probe | ffprobe; the first audio stream is required. The first video stream gives codec, size, frame rate and rotation; cover art does not count as video. A refused file keeps its reason for its tile |
| Prepare | One job: 16 kHz mono PCM audio, a thumbnail for video and a playback proxy, with weighted progress. It always starts from an empty `cache/`, so it also serves Rebuild; the transcript is kept |
| Thumbnail | One frame 320 px wide, at 10 % of the clip and never later than 5 s in |
| Proxy | `cache/proxy.mp4`, at most 720 px tall, H.264 with a keyframe every 30 frames and AAC audio (VideoToolbox when available, otherwise libx264); `cache/proxy.m4a` for audio clips. Timestamps are kept so proxy time equals source time. Export never uses it |
| Timeline media | `cache/levels.bin`: 100 RMS levels a second, made on first request. `cache/filmstrip.jpg`: keyframes of the proxy tiled into one image, made in the background |

## Jobs

| Area | Decision |
|---|---|
| Queue | Heavy jobs (prepare, transcribe, export) run one at a time in the order queued. One job per clip and one export per project at a time; otherwise 409 |
| States | QUEUED → RUNNING → SUCCEEDED, FAILED or CANCELLED; QUEUED → CANCELLED |
| Memory and disk | Jobs live in memory; each one's start and end are also written to `project.json`. A job left RUNNING when the server stopped is recorded as FAILED, "Interrupted by restart" |
| Restart | An interrupted upload starts again and an interrupted preparation fails. Clips left transcribing, or prepared with no transcript, are queued again and resume from their finished chunks, unless their last transcription was cancelled |
| Events | Server-sent events per job and per project. A stream starts with the full current state, so a reconnect needs no replay. Progress is sent on a 250 ms tick, status and queue changes at once, with a heartbeat every 15 s |
| ETA | Measured only from work done in the current run, so reused work does not make it too low |

## Transcription

| Area | Decision |
|---|---|
| Engine | whisper.cpp `small.en`, English, 4 threads (`app.transcription.threads`), JSON output. Peak memory stays under 1.2 GB |
| Chunks | 10-minute chunks with a 2-second overlap. Each finished chunk's JSON stays in `cache/whisper/` for resuming; chunk WAVs are deleted once read |
| Prompt | None. A per-project word list is not worth the complexity: deleting a misheard word still cuts the right audio, because cuts use word times, not spelling |
| Decoding | Beam search width 5, which keeps more repeated words at some cost in speed |
| Repetition loops | Guarded by `app.transcription.loop-guard`: `max-context-zero` (`-mc 0`, the default), `vad` (with `app.transcription.vad-model`) or `none`. A phrase of 2 to 12 words repeated three or more times in a row also keeps only its first copy. Single words are never dropped ("no, no, no" is real speech) |
| DTW | Off by default (`app.transcription.dtw`). It only works with flash attention off, so turning it on adds `-nfa --dtw <model>` |
| Non-speech | Bracketed spans such as `[BLANK_AUDIO]`, `(laughs)` and `*music*` are dropped, even when split across tokens, and so are words with no letter or digit |
| Words | Ids `w00001` upward per clip, given after the chunks are stitched; times in milliseconds on the clip's source; a word lasts at least 20 ms |
| Pause refinement | Word edges are tightened onto speech using 10 ms energy frames: noise floor at the 20th percentile, speech at 3× the floor, 20 ms hangover and lead-in, with a floor of about -60 dBFS so digital silence is never loud |
| Paragraphs | A paragraph ends after a sentence followed by 1.5 s of silence, or at 120 words |
| Cancel and retry | A cancelled transcription returns the clip to PREPARED; a failed one leaves it FAILED with Retry |

## Editing

| Area | Decision |
|---|---|
| Edit state | One document saved whole: timeline order, removed tokens, pause trims, padding, range cuts and splits. Each save bumps a revision sent as the `ETag`; saves send `If-Match`, and a stale one gets 409. Edits autosave and are undoable |
| Tokens | A clip is a lead-in pause, then each word and the pause after it. Cuts are stored by token id, so they survive moving a clip or taking it off the timeline. Tokens and cuts never span two clips |
| Planning | The server's EditPlanner is the single source of truth for what is kept; the client mirrors it for playback. Consecutive removed tokens merge into one cut, and a pause between two removed tokens goes with them |
| Padding | Default 30 ms, 0 to 200 ms. At a cut's edge the padding moves into the cut next to a kept word, or out into the kept pause next to a removed word, never past that pause's middle |
| Pauses | A trimmed pause keeps the chosen length, centred, and its cut never merges with others. Max pause replaces every SILENCE range cut with the silences found in the audio, as one undoable edit |
| Range cuts | MANUAL (a piece deleted on the timeline) or SILENCE. They take out exactly their range, with no padding, and the words inside go with them |
| Splits | Split at the playhead; Delete left and Delete right remove from the playhead to the split before or after it. A split too close to an edge or another split is ignored |
| Tiny pieces | A keep segment shorter than 20 ms is absorbed into the cut next to it |
| Timeline | Ready clips are dragged from the Clips tray onto the timeline, reordered along it and dragged back to take them off; each move is one undoable edit. The transcript follows timeline order. With Skip cuts on, the timeline shows edited time |

## Export

| Area | Decision |
|---|---|
| Plan | Built on the server from the saved edits, never from the browser's numbers |
| Audio | Every export's audio is 48 kHz stereo, with 10 ms fades at each join but not at the export's own start and end |
| Video | The output size and frame rate come from the first video clip on the timeline, rounded down to even dimensions. Keep segments are snapped to that frame rate so audio and video stay in sync across hundreds of joins; variable frame rate is fixed before trimming. Other clips are fitted inside the frame with black bars, never cropped. An audio clip in an MP4 gets black video. MP3 and WAV keep the planner's milliseconds |
| Graph | One FFmpeg run with the filter graph in a file (`export-<jobId>.filtergraph`), since hundreds of segments would overflow a command line. The graph file is deleted whatever the outcome. Numbers use a dot and three decimals whatever the locale |
| Encoders | MP4: VideoToolbox at 8 Mb/s (Standard) or 16 Mb/s (High) for 1080p, scaled by pixel count, libx264 when VideoToolbox is missing, AAC audio. MP3: LAME. WAV: 16-bit or 24-bit PCM |
| Final check | The output is written to a `.partial` file and renamed only after ffprobe confirms every stream and a length within max(100 ms, 0.5 %) of the plan |
| Rules | 409 while a timeline clip is not ready; 400 when nothing is left to export; 400 for an MP4 with no video clip on the timeline (the dialog hides MP4 then) |
| Files | `projects/<id>/exports/<name>-edited-<yyyyMMdd-HHmmss>.<ext>`, so an export never overwrites an earlier one. The dialog reads the newest entry of `GET /exports` once the job succeeds. Show in Finder uses `open -R` |

## Client app

| Area | Decision |
|---|---|
| Delivery | An installable PWA, installed from Chrome on the Mac only. The install dialog is Chrome's own; manual steps show if Chrome cancels it or offers none within 10 seconds |
| Routes | Onboarding `/` (the manifest's start URL) and Install `/install` work in a browser tab. Every other route needs the installed app and sends a tab back to Onboarding. `VITE_SKIP_INSTALL_GUARD=true` lets `npm run dev` run the whole app in a tab; a build ignores it |
| First run | Service down, then Setup while a tool is missing, then Home. Service down has no Try again button: the status is polled every 3 seconds |
| Updates | A new build is offered within 3 minutes, or when the app comes back to the front. Updating resets the client's own files only; projects live on the server |
| Data | TanStack Query in memory only. Spinners use `isPending`, never `isFetching`. Only server errors are retried, twice. Every path, route and query key is defined once |
| Home | One "Add video or audio for editing" button that also takes a file dropped anywhere, then the Projects grid. Rename in place: Enter or clicking away saves, Escape cancels. Delete asks in a modal |
| Editor | Video pane, transcript with a Cuts tab, Clips tray and timeline with waveform, filmstrip, ruler and playhead. Files dropped in the editor join the open project; files added from Home make a new one. Nothing goes on the timeline until a ready clip is dragged there |
| Clip tiles | Uploading, Preparing and Transcribing with a percentage; "Waiting (n ahead)"; Failed with Retry; Upload stopped with Try again; "No speech found"; "Not on timeline". Deleting a clip removes it from the tray, the queue and the timeline and cannot be undone |
| Look | Dark only. Every colour, size and duration is a design token in `tokens.css` (palette, semantic, component); Stylelint rejects raw values elsewhere. One font, Red Hat Text 400, self-hosted. The accent is white, with dark text on it. Reduced motion is honoured everywhere |
| App icon | The owner's Dettavo logo, kept exactly as supplied in `assets/icon-source.png`. `npm run icons` only scales it; the maskable and Apple icons place it on `#1e1e1e` |
