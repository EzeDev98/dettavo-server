package com.dettavo.feature.transcription.service;

import com.dettavo.config.ApplicationProperties;
import com.dettavo.feature.project.dto.Paragraph;
import com.dettavo.feature.project.dto.Word;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ParagraphBuilder {

    private final long pauseMs;
    private final int maxWords;

    public ParagraphBuilder(ApplicationProperties properties) {
        this.pauseMs = properties.transcription().paragraphPauseMs();
        this.maxWords = properties.transcription().paragraphMaxWords();
    }

    public List<Paragraph> build(List<Word> words) {
        List<Paragraph> paragraphs = new ArrayList<>();
        int first = 0;
        for (int index = 0; index < words.size(); index++) {
            Word word = words.get(index);
            boolean last = index == words.size() - 1;
            boolean sentenceThenSilence = !last && endsSentence(word.text())
                    && words.get(index + 1).startMs() - word.endMs() >= pauseMs;
            if (last || sentenceThenSilence || index - first + 1 >= maxWords) {
                paragraphs.add(new Paragraph(String.format("p%03d", paragraphs.size() + 1), words.get(first).id(), word.id()));
                first = index + 1;
            }
        }
        return paragraphs;
    }

    /** "done." and "right?" end a sentence, and so do "done.)" and "right?\"". */
    private static boolean endsSentence(String text) {
        String trimmed = text.replaceAll("[\"')\\]]+$", "");
        return trimmed.endsWith(".") || trimmed.endsWith("?") || trimmed.endsWith("!");
    }
}
