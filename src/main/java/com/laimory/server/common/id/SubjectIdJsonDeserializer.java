package com.laimory.server.common.id;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** Redis 내부 task JSON 전용 subject deserializer. */
public class SubjectIdJsonDeserializer extends JsonDeserializer<SubjectId> {

    @Override
    public SubjectId deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        try {
            return SubjectIdCodec.decode(parser.getValueAsString());
        } catch (RuntimeException e) {
            throw context.weirdStringException(parser.getValueAsString(), SubjectId.class,
                    "invalid encoded subject id");
        }
    }
}
