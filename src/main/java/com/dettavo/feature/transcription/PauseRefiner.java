package com.dettavo.feature.transcription;

import com.dettavo.config.ApplicationProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Tightens word edges with the energy of the clip's 16 kHz audio, so pauses are as long as they really are
 * (guide 6.4). Edges only ever move inwards, towards speech: a pause can grow but never shrink. Whisper often stretches
 * a word over the silence after it, so an edge may move as far as it takes to reach the word's own sound (up to
 * max-edge-shift-ms), never closer than TimedWord.MIN_DURATION_MS to the other edge. A word with no loud frame at all
 * is left as whisper timed it. Refining every word's start and end also refines the clip's lead-in (first word's start)
 * and tail (last word's end).
 */
@Component
public class PauseRefiner {

    private static final int FRAME_MS = 10;
    private static final int BUFFER_BYTES = 1 << 16;
    /** About -60 dBFS: digital silence would otherwise give a threshold of 0 and nothing would ever be quiet. */
    private static final double MINIMUM_THRESHOLD = 0.001;

    private final ApplicationProperties.Transcription settings;

    public PauseRefiner(ApplicationProperties properties) {
        this.settings = properties.transcription();
    }

    public List<TimedWord> refine(List<TimedWord> words, Path audio, WavAudio wav) {
        if (words.isEmpty()) {
            return words;
        }
        double[] energy = frameEnergies(audio, wav);
        if (energy.length == 0) {
            return words;
        }
        double threshold = threshold(energy);
        return words.stream().map(word -> refine(word, energy, threshold)).toList();
    }

    private TimedWord refine(TimedWord word, double[] energy, double threshold) {
        if (!hasSound(word, energy, threshold)) {
            return word;
        }
        long startLimit = Math.min(word.startMs() + settings.maxEdgeShiftMs(), word.endMs() - TimedWord.MIN_DURATION_MS);
        long start = refinedStart(word.startMs(), startLimit, energy, threshold);
        long endLimit = Math.max(word.endMs() - settings.maxEdgeShiftMs(), start + TimedWord.MIN_DURATION_MS);
        long end = refinedEnd(word.endMs(), endLimit, energy, threshold);
        if (end - start < TimedWord.MIN_DURATION_MS) {
            return word;
        }
        return word.withTimes(start, end);
    }

    /** Whether any 10 ms frame inside the word is loud: without one there is nothing to tighten the edges onto. */
    private static boolean hasSound(TimedWord word, double[] energy, double threshold) {
        int first = (int) Math.max(0, word.startMs() / FRAME_MS);
        int last = (int) Math.min((word.endMs() - 1) / FRAME_MS, energy.length - 1);
        for (int frame = first; frame <= last; frame++) {
            if (energy[frame] >= threshold) {
                return true;
            }
        }
        return false;
    }

    /** Steps forwards from the start while quiet, never past limit; the new start is the first loud frame minus the lead-in. */
    private long refinedStart(long startMs, long limitMs, double[] energy, double threshold) {
        int frame = (int) (startMs / FRAME_MS);
        if (frame >= energy.length) {
            return startMs;
        }
        int first = frame;
        while (first < energy.length && (long) (first + 1) * FRAME_MS <= limitMs && energy[first] < threshold) {
            first++;
        }
        if (first == frame) {
            return startMs;
        }
        return Math.max((long) first * FRAME_MS - settings.leadInMs(), startMs);
    }

    /** Steps backwards from the end while quiet, never past limit; the new end is the last loud frame plus the hangover. */
    private long refinedEnd(long endMs, long limitMs, double[] energy, double threshold) {
        int frame = (int) Math.min((endMs - 1) / FRAME_MS, energy.length - 1);
        if (frame < 0) {
            return endMs;
        }
        int last = frame;
        while (last >= 0 && (long) last * FRAME_MS >= limitMs && energy[last] < threshold) {
            last--;
        }
        if (last == frame) {
            return endMs;
        }
        return Math.min((long) (last + 1) * FRAME_MS + settings.hangoverMs(), endMs);
    }

    /** Noise floor = the configured percentile of all frame energies; speech threshold = floor x factor. */
    private double threshold(double[] energy) {
        double[] sorted = energy.clone();
        Arrays.sort(sorted);
        int index = (int) Math.floor(settings.noisePercentile() / 100.0 * (sorted.length - 1));
        return Math.max(sorted[index] * settings.speechFactor(), MINIMUM_THRESHOLD);
    }

    /** RMS of each 10 ms frame, on samples scaled to -1..1. Multi-channel audio is read as interleaved samples. */
    static double[] frameEnergies(Path audio, WavAudio wav) {
        int frameBytes = wav.sampleRate() * FRAME_MS / 1000 * wav.blockAlign();
        int frames = (int) (wav.dataBytes() / frameBytes);
        double[] energy = new double[frames];
        byte[] frame = new byte[frameBytes];
        int samples = frameBytes / 2;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(audio), BUFFER_BYTES)) {
            in.skipNBytes(wav.dataOffset());
            for (int index = 0; index < frames; index++) {
                if (in.readNBytes(frame, 0, frameBytes) < frameBytes) {
                    return Arrays.copyOf(energy, index);
                }
                double sum = 0;
                for (int sample = 0; sample < samples; sample++) {
                    short value = (short) ((frame[2 * sample] & 0xff) | (frame[2 * sample + 1] << 8));
                    double scaled = value / 32768.0;
                    sum += scaled * scaled;
                }
                energy[index] = Math.sqrt(sum / samples);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + audio, e);
        }
        return energy;
    }
}
