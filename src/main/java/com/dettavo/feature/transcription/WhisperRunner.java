package com.dettavo.feature.transcription;

import com.dettavo.common.exception.ToolFailedException;
import com.dettavo.common.process.CancellationToken;
import com.dettavo.common.process.NativeProcessRunner;
import com.dettavo.common.process.ProcessResult;
import com.dettavo.common.process.ProcessSpecification;
import com.dettavo.common.process.ToolInventory;
import com.dettavo.common.process.ToolLocator;
import com.dettavo.config.ApplicationProperties;
import com.dettavo.feature.media.ClipFiles;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs whisper-cli on one chunk WAV (guide 6.2). The flags are fixed by configuration, never by a request:
 * -l en -t 4 -ojf -pp, beam search when beam-size is above 0, then DTW and the repetition-loop guard from checks 1d
 * and 1j. There is no prompt: with -mc 0 whisper ignores one.
 * Progress comes from the "progress = N%" lines -pp writes to standard error.
 */
@Component
public class WhisperRunner {

    private static final Pattern PROGRESS = Pattern.compile("progress\\s*=\\s*(\\d+)\\s*%");
    private static final String PARTIAL_SUFFIX = "-partial";
    private static final String JSON = ".json";

    private final ToolLocator toolLocator;
    private final NativeProcessRunner nativeProcessRunner;
    private final ApplicationProperties.Transcription settings;
    private final Path modelFile;

    public WhisperRunner(ToolLocator toolLocator, NativeProcessRunner nativeProcessRunner, ApplicationProperties properties) {
        this.toolLocator = toolLocator;
        this.nativeProcessRunner = nativeProcessRunner;
        this.settings = properties.transcription();
        this.modelFile = properties.tools().whisperModel();
    }

    /** "small.en" from ggml-small.en.bin: stored with the transcript, and the --dtw preset name. */
    public String modelName() {
        return modelFile.getFileName().toString().replaceFirst("^ggml-", "").replaceFirst("\\.bin$", "");
    }

    /** Whether word starts should come from DTW token times. */
    public boolean usesDtw() {
        return settings.dtw();
    }

    /**
     * Transcribes audio into target (cache/whisper/chunk-NNNN.json). onProgress gets 0..1 for this chunk.
     * Returns false if cancelled, leaving no JSON behind.
     */
    public boolean transcribe(Path audio, Path target, Path logFile,
                              CancellationToken cancellationToken, DoubleConsumer onProgress) {
        String name = target.getFileName().toString();
        Path partialBase = target.resolveSibling(name.substring(0, name.length() - JSON.length()) + PARTIAL_SUFFIX);
        Path output = partialBase.resolveSibling(partialBase.getFileName() + JSON);
        ClipFiles.discard(output);

        ProcessResult result;
        try {
            result = nativeProcessRunner.run(new ProcessSpecification(command(audio, partialBase), null, null,
                    null, progressListener(onProgress), logFile), cancellationToken);
        } catch (RuntimeException e) {
            ClipFiles.discard(output);
            throw e;
        }
        if (result.cancelled()) {
            ClipFiles.discard(output);
            return false;
        }
        if (!Files.isRegularFile(output)) {
            throw new ToolFailedException(ToolLocator.WHISPER_CLI, "finished without writing " + output.getFileName(),
                    logFile, result.standardErrorTail());
        }
        ClipFiles.commit(output, target);
        onProgress.accept(1.0);
        return true;
    }

    private List<String> command(Path audio, Path outputBase) {
        List<String> command = new ArrayList<>(List.of(
                whisperCli().toString(),
                "-m", model().toString(),
                "-f", audio.toString(),
                "-l", settings.language(),
                "-t", String.valueOf(settings.threads()),
                "-ojf",
                "-pp",
                "-of", outputBase.toString()));
        if (settings.beamSize() > 0) {
            // Weighs several candidate transcriptions, so short repeated words ("I I", "so so") are kept more often.
            command.addAll(List.of("-bs", String.valueOf(settings.beamSize())));
        }
        if (settings.dtw()) {
            // DTW only takes effect with flash attention off (Step 1).
            command.addAll(List.of("-nfa", "--dtw", modelName()));
        }
        switch (settings.loopGuard()) {
            case MAX_CONTEXT_ZERO -> command.addAll(List.of("-mc", "0"));
            case VAD -> command.addAll(List.of("--vad", "-vm", vadModel().toString()));
            case NONE -> {
                // Unguarded; the normalizer still drops a phrase repeated three or more times in a row.
            }
        }
        return command;
    }

    private Path whisperCli() {
        ToolInventory.Tool tool = toolLocator.inventory().whisperCli();
        if (tool == null || !tool.available() || tool.path() == null) {
            throw new ToolFailedException(ToolLocator.WHISPER_CLI, "not found; see the Setup screen", null, List.of());
        }
        return Path.of(tool.path());
    }

    private Path model() {
        ToolInventory.Model model = toolLocator.inventory().model();
        if (model == null || !model.present()) {
            throw new ToolFailedException(ToolLocator.WHISPER_CLI, "the model " + modelFile + " is missing; see the Setup screen",
                    null, List.of());
        }
        return Path.of(model.path());
    }

    private Path vadModel() {
        Path vad = settings.vadModel().toAbsolutePath().normalize();
        if (!Files.isRegularFile(vad)) {
            throw new ToolFailedException(ToolLocator.WHISPER_CLI, "the VAD model " + vad + " is missing", null, List.of());
        }
        return vad;
    }

    static Consumer<String> progressListener(DoubleConsumer onProgress) {
        return line -> {
            Matcher matcher = PROGRESS.matcher(line);
            if (matcher.find()) {
                onProgress.accept(Math.clamp(Integer.parseInt(matcher.group(1)) / 100.0, 0.0, 1.0));
            }
        };
    }
}
