package com.dettavo.common.exception;

public class PairingKeyInvalidException extends RuntimeException {

    public PairingKeyInvalidException() {
        super("The pairing key is not valid. Copy it again from the server log or ~/Dettavo/pairing.key");
    }
}
