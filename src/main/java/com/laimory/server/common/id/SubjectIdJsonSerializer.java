package com.laimory.server.common.id;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/** Redis 내부 task JSON 전용 subject serializer. */
public class SubjectIdJsonSerializer extends JsonSerializer<SubjectId> {

    @Override
    public void serialize(SubjectId value, JsonGenerator generator, SerializerProvider serializers)
            throws IOException {
        generator.writeString(SubjectIdCodec.encode(value));
    }
}
