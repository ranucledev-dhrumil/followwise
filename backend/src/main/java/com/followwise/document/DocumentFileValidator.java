package com.followwise.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class DocumentFileValidator {

    private DocumentFileValidator() {}

    public record DetectedFile(String contentType, String extension) {}

    public static DetectedFile detectFileType(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new DocumentBadRequestException("empty body not allowed");
        }
        // PDF: %PDF-
        if (bytes.length >= 5 && bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46 && bytes[4] == 0x2D) {
            return new DetectedFile("application/pdf", ".pdf");
        }
        // PNG: \x89PNG\r\n\x1a\n
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return new DetectedFile("image/png", ".png");
        }
        // JPEG: \xFF\xD8
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return new DetectedFile("image/jpeg", ".jpeg");
        }
        throw new DocumentBadRequestException("unsupported or invalid file type");
    }

    public static String sanitizeFilename(String rawHeader, String extension) {
        String decoded = "document";
        if (rawHeader != null && !rawHeader.isBlank()) {
            try {
                decoded = java.net.URLDecoder.decode(rawHeader.trim(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                decoded = "document";
            }
        }
        int lastSlash = Math.max(decoded.lastIndexOf('/'), decoded.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < decoded.length() - 1) {
            decoded = decoded.substring(lastSlash + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            if (c >= 0x20 && c != 0x7F && c != '"' && c != '\'') {
                sb.append(c);
            }
        }
        String clean = sb.toString().trim();
        int dot = clean.lastIndexOf('.');
        if (dot > 0) {
            clean = clean.substring(0, dot).trim();
        }
        if (clean.isEmpty()) {
            clean = "document";
        }
        int maxBaseLen = 100 - extension.length();
        if (clean.length() > maxBaseLen) {
            clean = clean.substring(0, maxBaseLen);
        }
        return clean + extension;
    }

    public static String computeSha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
