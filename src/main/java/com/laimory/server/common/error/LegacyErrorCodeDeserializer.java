package com.laimory.server.common.error;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 호환 기간의 error code input adapter.
 *
 * <p>신규 JSON integer와 exact legacy {@code ERROR_XXXX}만 Integer로 정규화한다. 여기서는 code의
 * 의미를 역해석하지 않는다. task/callback 경계가 각자 allowlist와 fallback을 적용한다.
 */
public class LegacyErrorCodeDeserializer extends JsonDeserializer<Integer> {

    private static final Pattern LEGACY_CODE = Pattern.compile("^ERROR_([0-9]{4})$");

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            BigInteger value = parser.getBigIntegerValue();
            if (value.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0
                    || value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                return null;
            }
            return value.intValue();
        }
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            Matcher matcher = LEGACY_CODE.matcher(parser.getText());
            return matcher.matches() ? -Integer.parseInt(matcher.group(1)) : null;
        }
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        parser.skipChildren();
        return null;
    }
}
