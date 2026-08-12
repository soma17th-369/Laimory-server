package com.laimory.server.common.id;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** JPA의 non-ID {@link SubjectId} 속성과 MySQL {@code BINARY(16)} 사이의 canonical 변환기. */
@Converter(autoApply = true)
public class SubjectIdAttributeConverter implements AttributeConverter<SubjectId, byte[]> {

    @Override
    public byte[] convertToDatabaseColumn(SubjectId attribute) {
        return attribute == null ? null : attribute.bytes();
    }

    @Override
    public SubjectId convertToEntityAttribute(byte[] dbData) {
        return dbData == null ? null : SubjectId.fromBytes(dbData);
    }
}
