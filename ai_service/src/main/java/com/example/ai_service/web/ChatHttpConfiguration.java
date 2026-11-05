package com.example.ai_service.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import java.util.List;

@Configuration(proxyBeanMethods=false)
public class ChatHttpConfiguration {
    private static final int MAX_NESTING_DEPTH=32;
    private static final int MAX_STRING_LENGTH=128000;
    private static final int MAX_NUMBER_LENGTH=20;
    private static final int MAX_NAME_LENGTH=80;
    @Bean Jackson2ObjectMapperBuilderCustomizer strictChatJson() {
        return builder->builder.postConfigurer(mapper->{
            mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_NESTING_DEPTH).maxStringLength(MAX_STRING_LENGTH)
                    .maxNumberLength(MAX_NUMBER_LENGTH).maxNameLength(MAX_NAME_LENGTH).build());
            mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
            mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
            for(var type:List.of(LogicalType.Integer,LogicalType.Float,LogicalType.Boolean))
                mapper.coercionConfigFor(type).setCoercion(CoercionInputShape.String,CoercionAction.Fail);
            for(var shape:List.of(CoercionInputShape.Integer,CoercionInputShape.Float,CoercionInputShape.Boolean))
                mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape,CoercionAction.Fail);
        });
    }
}
