package com.dettavo.feature.session.dto;

import jakarta.validation.constraints.NotBlank;

/** The pairing key the pair page read from its URL fragment. */
public record PairRequest(@NotBlank String key) {
}
