package com.dettavo.feature.transcription.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class WhisperJsonParser {

    private final JsonMapper jsonMapper;

    /**
     * One token. Times are milliseconds from the start of the chunk.
     *
     * @param dtwMs           the DTW time of the token, or -1 when whisper ran without --dtw
     * @param firstInSegment  whisper's segments always start a new word
     */
    public record Token(String text, long fromMs, long toMs, double probability, long dtwMs, boolean firstInSegment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(List<Segment> transcription) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(List<TokenJson> tokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenJson(String text, Offsets offsets, Double p, @JsonProperty("t_dtw") Long tDtw) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Offsets(Long from, Long to) {
    }

    public List<Token> parse(Path file) {
        Output output;
        try (InputStream in = Files.newInputStream(file)) {
            output = jsonMapper.readValue(in, Output.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        } catch (JacksonException e) {
            throw new IllegalStateException("whisper's output " + file.getFileName() + " cannot be read: " + e.getOriginalMessage(), e);
        }
        List<Token> tokens = new ArrayList<>();
        if (output == null || output.transcription() == null) {
            return tokens;
        }
        for (Segment segment : output.transcription()) {
            if (segment == null || segment.tokens() == null) {
                continue;
            }
            boolean first = true;
            for (TokenJson token : segment.tokens()) {
                if (token == null || token.text() == null || token.offsets() == null) {
                    continue;
                }
                long from = token.offsets().from() == null ? 0 : token.offsets().from();
                long to = token.offsets().to() == null ? from : token.offsets().to();
                // t_dtw is in 10 ms units; -1 means none.
                long dtw = token.tDtw() == null || token.tDtw() < 0 ? -1 : token.tDtw() * 10;
                tokens.add(new Token(token.text(), from, to, token.p() == null ? 0 : token.p(), dtw, first));
                first = false;
            }
        }
        return tokens;
    }
}
