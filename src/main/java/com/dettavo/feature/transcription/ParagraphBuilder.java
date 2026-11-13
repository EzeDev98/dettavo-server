package com.dettavo.feature.transcription;

import com.dettavo.config.ApplicationProperties;
import com.dettavo.feature.project.model.Paragraph;
import com.dettavo.feature.project.model.Word;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups a clip's words into paragraphs (guide 6.5): a new one starts after a word ending in . ? or ! when the
 * pause after it is at least 1.5 s, or when the paragraph already has 120 words. Every clip starts a new paragraph,
 * because each clip's transcript is built on its own.
 */
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
