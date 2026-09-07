package com.laimory.server.terms;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 약관의 canonical {@code major.minor} 버전. DB/API 식별자는 문자열로 유지하되 current 비교는 두
 * segment를 숫자로 수행해 {@code 1.10 > 1.9}를 보장한다.
 */
public record TermVersion(BigInteger major, BigInteger minor) implements Comparable<TermVersion> {

    public static final int MAX_LENGTH = 64;
    public static final String PATTERN_TEXT = "^([1-9][0-9]*)[.](0|[1-9][0-9]*)$";
    private static final Pattern PATTERN = Pattern.compile(PATTERN_TEXT);

    public TermVersion {
        if (major == null || minor == null || major.signum() <= 0 || minor.signum() < 0) {
            throw new IllegalArgumentException("term version requires major >= 1 and minor >= 0");
        }
    }

    public static TermVersion parse(String value) {
        if (value == null || value.length() > MAX_LENGTH) {
            throw invalid(value);
        }
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw invalid(value);
        }
        return new TermVersion(new BigInteger(matcher.group(1)), new BigInteger(matcher.group(2)));
    }

    public static boolean isCanonical(String value) {
        try {
            parse(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override
    public int compareTo(TermVersion other) {
        int majorComparison = major.compareTo(other.major);
        return majorComparison != 0 ? majorComparison : minor.compareTo(other.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException("invalid canonical term version: " + value);
    }
}
