package com.example.tour_service.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeParseException;

/** Departure wall times are stored in Vietnam time; offset-bearing clients are converted explicitly. */
public class VietnamLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
    @Override public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String value = parser.getValueAsString();
        if (value == null) return (LocalDateTime) context.handleUnexpectedToken(LocalDateTime.class, parser);
        try { return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDateTime(); }
        catch (DateTimeParseException noOffset) {
            try { return LocalDateTime.parse(value); }
            catch (DateTimeParseException invalid) {
                return (LocalDateTime) context.handleWeirdStringValue(LocalDateTime.class, value, "Ngày giờ khởi hành không hợp lệ");
            }
        }
    }
}
