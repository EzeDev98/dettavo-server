package com.dettavo.feature.media.service;

import com.dettavo.common.exception.ClipNotFoundException;
import com.dettavo.common.exception.MediaNotFoundException;
import com.dettavo.common.process.CancellationToken;
import com.dettavo.config.ApplicationProperties;
import com.dettavo.feature.media.dto.response.FilmstripResponse;
import com.dettavo.feature.project.service.ProjectStore;
import com.dettavo.feature.project.dto.Clip;
import com.dettavo.feature.project.dto.ClipSource;
import com.dettavo.feature.transcription.service.WavAudio;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the timeline draws inside each clip block: the audio level (the waveform, also used to find silences) and,
 * for a video clip, a filmstrip of frames. Both are cache files made on first request, so clips prepared before this
 * existed get them too, and both go when the clip's cache is rebuilt.
 * <ul>
 *   <li>cache/levels.bin: one byte per frame of 1000 / app.peaks.per-second ms, the RMS level of cache/audio16k.wav
 *   in dB, from -60 dB (0) to 0 dB (255). Fast enough to make inside the request.</li>
 *   <li>cache/filmstrip.jpg: frames of cache/proxy.mp4 tiled into one image. Made in the background from keyframes
 *   only; the manifest says BUILDING until it is there.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineMediaService {

    private static final double FLOOR_DB = -60.0;
    private static final int BUFFER_BYTES = 1 << 16;
    private static final int TILE_HEIGHT = 64;
    private static final int MAX_FRAMES = 600;
    private static final int MAX_COLUMNS = 20;
    private static final long MIN_INTERVAL_MS = 1_000;

    private final ProjectStore projectStore;
    private final ClipFiles clipFiles;
    private final FfmpegRunner ffmpegRunner;
    private final ApplicationProperties properties;

    private final Map<Path, Object> levelLocks = new ConcurrentHashMap<>();
    /** Filmstrips being made, and those whose making failed, keyed by "<proxy path>:<proxy size>-<modified>". */
    private final Map<String, Boolean> filmstripsBuilding = new ConcurrentHashMap<>();
    private final Map<String, Boolean> filmstripsFailed = new ConcurrentHashMap<>();
    private final ExecutorService filmstripExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "filmstrip");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    void stop() {
        filmstripExecutor.shutdownNow();
    }

    /** cache/levels.bin, made now if missing. 404 until the clip's audio has been extracted. */
    public Path levels(UUID projectId, String clipId) {
        Clip clip = clipOf(projectId, clipId);
        String audioPath = clip.artifacts() == null ? null : clip.artifacts().audio16k();
        if (audioPath == null) {
            throw new MediaNotFoundException("Clip " + clipId + " has no audio yet");
        }
        Path audio = clipFiles.artifact(projectId, clipId, audioPath);
        if (!Files.isRegularFile(audio)) {
            throw new MediaNotFoundException("The audio of clip " + clipId + " is missing; prepare the clip again");
        }
        Path target = clipFiles.artifact(projectId, clipId, ClipFiles.LEVELS);
        synchronized (levelLocks.computeIfAbsent(target, key -> new Object())) {
            if (!Files.isRegularFile(target)) {
                writeLevels(audio, target);
            }
        }
        return target;
    }

    private void writeLevels(Path audio, Path target) {
        WavAudio wav = WavAudio.read(audio);
        int frameMs = Math.max(1, 1000 / properties.peaks().perSecond());
        int frameBytes = (int) ((long) wav.sampleRate() * frameMs / 1000 * wav.blockAlign());
        int samples = frameBytes / 2;
        int frames = (int) (wav.dataBytes() / frameBytes);
        byte[] levels = new byte[frames];
        byte[] frame = new byte[frameBytes];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(audio), BUFFER_BYTES)) {
            in.skipNBytes(wav.dataOffset());
            for (int index = 0; index < frames; index++) {
                if (in.readNBytes(frame, 0, frameBytes) < frameBytes) {
                    frames = index;
                    break;
                }
                double sum = 0;
                for (int sample = 0; sample < samples; sample++) {
                    short value = (short) ((frame[2 * sample] & 0xff) | (frame[2 * sample + 1] << 8));
                    double scaled = value / 32768.0;
                    sum += scaled * scaled;
                }
                levels[index] = (byte) toLevel(Math.sqrt(sum / samples));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + audio, e);
        }

        Path partial = ClipFiles.partialOf(target);
        try {
            ClipFiles.createDirectories(target.getParent());
            Files.write(partial, frames == levels.length ? levels : Arrays.copyOf(levels, frames));
        } catch (IOException e) {
            ClipFiles.discard(partial);
            throw new UncheckedIOException("Cannot write " + target, e);
        }
        ClipFiles.commit(partial, target);
    }

    /** RMS (0..1) to 0..255 over FLOOR_DB..0 dB. */
    private static int toLevel(double rms) {
        if (rms <= 0) {
            return 0;
        }
        double db = 20 * Math.log10(rms);
        return (int) Math.round(Math.clamp((db - FLOOR_DB) / -FLOOR_DB, 0.0, 1.0) * 255);
    }

    /** The filmstrip's layout and whether its image is ready; starts making it if it is not. 404 for audio clips. */
    public FilmstripResponse filmstrip(UUID projectId, String clipId) {
        Clip clip = clipOf(projectId, clipId);
        Path proxy = videoProxyOf(projectId, clip);
        Layout layout = layoutOf(clip.source());
        Path target = clipFiles.artifact(projectId, clipId, ClipFiles.FILMSTRIP);

        String status;
        if (Files.isRegularFile(target)) {
            status = FilmstripResponse.READY;
        } else {
            String key = keyOf(proxy);
            if (filmstripsFailed.containsKey(key)) {
                status = FilmstripResponse.FAILED;
            } else {
                if (filmstripsBuilding.putIfAbsent(key, Boolean.TRUE) == null) {
                    filmstripExecutor.submit(() -> buildFilmstrip(projectId, proxy, target, layout, clip.source().durationMs(), key));
                }
                status = FilmstripResponse.BUILDING;
            }
        }
        return new FilmstripResponse(status, layout.intervalMs(), layout.count(), layout.columns(), layout.tileWidth(), TILE_HEIGHT);
    }

    /** cache/filmstrip.jpg once it is made. */
    public Path filmstripImage(UUID projectId, String clipId) {
        clipOf(projectId, clipId);
        Path target = clipFiles.artifact(projectId, clipId, ClipFiles.FILMSTRIP);
        if (!Files.isRegularFile(target)) {
            throw new MediaNotFoundException("The filmstrip of clip " + clipId + " is not ready yet");
        }
        return target;
    }

    private void buildFilmstrip(UUID projectId, Path proxy, Path target, Layout layout, Long durationMs, String key) {
        try {
            int rows = (layout.count() + layout.columns() - 1) / layout.columns();
            String interval = String.format(Locale.ROOT, "%.3f", layout.intervalMs() / 1000.0);
            List<String> arguments = List.of(
                    "-skip_frame", "nokey",
                    "-i", proxy.toString(),
                    "-map", "0:v:0", "-an",
                    "-vf", "fps=1/" + interval + ",scale=" + layout.tileWidth() + ":" + TILE_HEIGHT
                            + ",tile=" + layout.columns() + "x" + rows,
                    "-frames:v", "1",
                    "-q:v", "5",
                    "-f", "image2");
            Path logFile = clipFiles.jobLog(projectId, UUID.randomUUID());
            ffmpegRunner.runToFile(arguments, target, durationMs == null ? 0 : durationMs, logFile,
                    CancellationToken.none(), fraction -> {
                    });
        } catch (RuntimeException e) {
            filmstripsFailed.put(key, Boolean.TRUE);
            log.warn("Could not make the filmstrip {}: {}", target, e.getMessage());
        } finally {
            filmstripsBuilding.remove(key);
        }
    }

    /** One frame every intervalMs (at least 1 s, and at most MAX_FRAMES frames), each TILE_HEIGHT tall. */
    private static Layout layoutOf(ClipSource source) {
        long durationMs = source.durationMs() == null ? 0 : Math.max(0, source.durationMs());
        long intervalMs = Math.max(MIN_INTERVAL_MS, ceilTo(Math.ceilDiv(durationMs, MAX_FRAMES), MIN_INTERVAL_MS));
        int count = (int) Math.max(1, (durationMs + intervalMs - 1) / intervalMs);
        int columns = Math.min(count, MAX_COLUMNS);

        int width = source.width() == null ? 16 : source.width();
        int height = source.height() == null ? 9 : source.height();
        Integer rotation = source.rotation();
        if (rotation != null && Math.abs(rotation) % 180 == 90) {
            int swap = width;
            width = height;
            height = swap;
        }
        int tileWidth = Math.max(2, (int) Math.round((double) TILE_HEIGHT * width / Math.max(1, height) / 2) * 2);
        return new Layout(intervalMs, count, columns, tileWidth);
    }

    private static long ceilTo(long value, long step) {
        return (value + step - 1) / step * step;
    }

    private record Layout(long intervalMs, int count, int columns, int tileWidth) {
    }

    private Clip clipOf(UUID projectId, String clipId) {
        return projectStore.load(projectId).findClip(clipId)
                .orElseThrow(() -> new ClipNotFoundException(projectId, clipId));
    }

    private Path videoProxyOf(UUID projectId, Clip clip) {
        if (!Boolean.TRUE.equals(clip.source() == null ? null : clip.source().hasVideo())) {
            throw new MediaNotFoundException("Clip " + clip.id() + " has no video");
        }
        String proxyPath = clip.artifacts() == null ? null : clip.artifacts().proxy();
        if (proxyPath == null) {
            throw new MediaNotFoundException("Clip " + clip.id() + " has no playback copy yet");
        }
        Path proxy = clipFiles.artifact(projectId, clip.id(), proxyPath);
        if (!Files.isRegularFile(proxy)) {
            throw new MediaNotFoundException("The playback copy of clip " + clip.id() + " is missing; prepare the clip again");
        }
        return proxy;
    }

    private static String keyOf(Path file) {
        try {
            return file + ":" + Files.size(file) + "-" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }
}
