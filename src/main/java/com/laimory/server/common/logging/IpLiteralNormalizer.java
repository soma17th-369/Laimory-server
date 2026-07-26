package com.laimory.server.common.logging;

import java.util.ArrayList;
import java.util.List;

/**
 * DNS 조회 없이 IPv4/IPv6 literal만 검증하고 정규화한다.
 */
final class IpLiteralNormalizer {

    private IpLiteralNormalizer() {
    }

    static String normalize(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        String value = trimOptionalWhitespace(rawValue);
        if (value.isEmpty()) {
            return null;
        }

        String ipv4 = normalizeIpv4(value);
        if (ipv4 != null) {
            return ipv4;
        }
        return normalizeIpv6(value);
    }

    private static String normalizeIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }

        int[] parsed = new int[4];
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (octet.isEmpty() || octet.length() > 3) {
                return null;
            }
            if (octet.length() > 1 && octet.charAt(0) == '0') {
                return null;
            }

            int number = 0;
            for (int charIndex = 0; charIndex < octet.length(); charIndex++) {
                char character = octet.charAt(charIndex);
                if (character < '0' || character > '9') {
                    return null;
                }
                number = number * 10 + character - '0';
            }
            if (number > 255) {
                return null;
            }
            parsed[index] = number;
        }

        return parsed[0] + "." + parsed[1] + "." + parsed[2] + "." + parsed[3];
    }

    private static String normalizeIpv6(String value) {
        if (value.indexOf(':') < 0) {
            return null;
        }

        String expandedIpv4 = expandEmbeddedIpv4(value);
        if (expandedIpv4 == null) {
            return null;
        }
        value = expandedIpv4;

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ':' && !isHexDigit(character)) {
                return null;
            }
        }

        int compression = value.indexOf("::");
        if (compression != value.lastIndexOf("::")) {
            return null;
        }

        List<Integer> left;
        List<Integer> right;
        if (compression >= 0) {
            left = parseHexGroups(value.substring(0, compression));
            right = parseHexGroups(value.substring(compression + 2));
            if (left == null || right == null || left.size() + right.size() >= 8) {
                return null;
            }
        } else {
            left = parseHexGroups(value);
            right = List.of();
            if (left == null || left.size() != 8) {
                return null;
            }
        }

        int[] groups = new int[8];
        for (int index = 0; index < left.size(); index++) {
            groups[index] = left.get(index);
        }
        for (int index = 0; index < right.size(); index++) {
            groups[groups.length - right.size() + index] = right.get(index);
        }
        return formatIpv6(groups);
    }

    /**
     * IPv4-mapped/embedded IPv6 literal의 마지막 IPv4 부분을 두 hex group으로 바꾼다.
     */
    private static String expandEmbeddedIpv4(String value) {
        int dot = value.indexOf('.');
        if (dot < 0) {
            return value;
        }

        int lastColon = value.lastIndexOf(':');
        if (lastColon < 0 || dot < lastColon) {
            return null;
        }

        String ipv4 = normalizeIpv4(value.substring(lastColon + 1));
        if (ipv4 == null) {
            return null;
        }
        String[] octets = ipv4.split("\\.");
        int high = Integer.parseInt(octets[0]) << 8 | Integer.parseInt(octets[1]);
        int low = Integer.parseInt(octets[2]) << 8 | Integer.parseInt(octets[3]);
        return value.substring(0, lastColon + 1)
                + Integer.toHexString(high) + ":" + Integer.toHexString(low);
    }

    private static List<Integer> parseHexGroups(String side) {
        if (side.isEmpty()) {
            return new ArrayList<>();
        }

        String[] tokens = side.split(":", -1);
        List<Integer> groups = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (token.isEmpty() || token.length() > 4) {
                return null;
            }
            try {
                groups.add(Integer.parseInt(token, 16));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return groups;
    }

    private static String formatIpv6(int[] groups) {
        int bestStart = -1;
        int bestLength = 0;
        for (int index = 0; index < groups.length; ) {
            if (groups[index] != 0) {
                index++;
                continue;
            }
            int end = index;
            while (end < groups.length && groups[end] == 0) {
                end++;
            }
            if (end - index > bestLength) {
                bestStart = index;
                bestLength = end - index;
            }
            index = end;
        }
        if (bestLength < 2) {
            bestStart = -1;
        }

        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < groups.length; ) {
            if (index == bestStart) {
                normalized.append("::");
                index += bestLength;
                continue;
            }
            if (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) != ':') {
                normalized.append(':');
            }
            normalized.append(Integer.toHexString(groups[index]));
            index++;
        }
        return normalized.toString();
    }

    private static boolean isHexDigit(char value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }

    private static String trimOptionalWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isOptionalWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isOptionalWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isOptionalWhitespace(char value) {
        return value == ' ' || value == '\t';
    }
}
