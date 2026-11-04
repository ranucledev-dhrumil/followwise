package com.followwise.party;

public class PartyNotFoundException extends RuntimeException {
    public PartyNotFoundException(String message) {
        super(message);
    }
}
