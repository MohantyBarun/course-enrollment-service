package com.seatsure.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stored and serialized as "2D" / "3D" / "IMAX" (Java enum names can't start with a digit). */
public enum ShowFormat {
    TWO_D("2D"), THREE_D("3D"), IMAX("IMAX");

    private final String label;

    ShowFormat(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }

    @JsonCreator
    public static ShowFormat fromLabel(String label) {
        for (ShowFormat f : values()) {
            if (f.label.equalsIgnoreCase(label) || f.name().equalsIgnoreCase(label)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown format: " + label);
    }

    @Converter
    public static class DbConverter implements AttributeConverter<ShowFormat, String> {
        @Override
        public String convertToDatabaseColumn(ShowFormat format) {
            return format == null ? null : format.label;
        }

        @Override
        public ShowFormat convertToEntityAttribute(String value) {
            return value == null ? null : fromLabel(value);
        }
    }
}
