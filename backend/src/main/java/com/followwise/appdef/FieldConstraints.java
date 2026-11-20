package com.followwise.appdef;

public final class FieldConstraints {

    private FieldConstraints() {}

    public static final int TEXT_MIN_LENGTH = 1;
    public static final int TEXT_MAX_LENGTH = 500;

    public static final int EMAIL_MAX_LENGTH = 254;

    public static final int PHONE_MIN_LENGTH = 5;
    public static final int PHONE_MAX_LENGTH = 32;

    public static final long INTEGER_MIN = -1_000_000_000L;
    public static final long INTEGER_MAX = 1_000_000_000L;

    public static final long MONEY_MIN_CENTS = 0L;
    public static final long MONEY_MAX_CENTS = 100_000_000_000L;

    public static final String DATE_FORMAT = "yyyy-MM-dd";

    public static final int MAX_DISPLAY_NAME_LENGTH = 200;
    public static final int MAX_DATA_BYTES = 16384;
}
