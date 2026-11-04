package com.followwise.appdef;

public record ValidationError(String path, String message) {
    public ValidationError {
        if (path == null || path.isEmpty()) {
            path = "#";
        } else if (path.length() > 200) {
            path = path.substring(0, 200);
        }
    }
}
