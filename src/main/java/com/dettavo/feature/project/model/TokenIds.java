package com.dettavo.feature.project.model;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Token ids (guide 4.9). Every id starts with its clip id: a word is c01/w00017, the pause after it is
 * c01/g:w00017, and the lead-in pause before a clip's first word is c01/g:start.
 */
public final class TokenIds {

    private static final Pattern TOKEN = Pattern.compile("(c\\d{2,})/(?:(w\\d+)|g:start|g:(w\\d+))");

    /** A parsed token id. wordId is null for a lead-in pause. */
    public record TokenId(String clipId, String wordId, boolean pause) {
    }

    private TokenIds() {
    }

    public static Optional<TokenId> parse(String tokenId) {
        if (tokenId == null) {
            return Optional.empty();
        }
        Matcher matcher = TOKEN.matcher(tokenId);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        if (matcher.group(2) != null) {
            return Optional.of(new TokenId(matcher.group(1), matcher.group(2), false));
        }
        return Optional.of(new TokenId(matcher.group(1), matcher.group(3), true));
    }
}
