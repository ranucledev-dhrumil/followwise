package com.followwise.app;

import java.io.IOException;

public class MaxPayloadSizeExceededException extends IOException {
    public MaxPayloadSizeExceededException(String message) {
        super(message);
    }
}
