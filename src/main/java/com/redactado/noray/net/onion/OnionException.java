package com.redactado.noray.net.onion;

/** A message cannot be sent through the onion network (no key, not enough relays, too large). */
public final class OnionException extends RuntimeException {
    public OnionException(String message) {
        super(message);
    }
}
