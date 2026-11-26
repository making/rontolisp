// Reads and writes the values of tiny-json (https://github.com/making/tiny-json) as
// application/json: a JsonObject, a JsonArray or a JsonNode. The application builds its
// JSON from those itself, so nothing maps one of its classes to JSON by reflection, which
// a program compiled from source has none of.
package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import am.ik.json.Json;
import am.ik.json.JsonArray;
import am.ik.json.JsonLexerException;
import am.ik.json.JsonNode;
import am.ik.json.JsonObject;
import am.ik.json.JsonParseException;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

public class TinyJsonHttpMessageConverter extends AbstractHttpMessageConverter<Object> {

    public TinyJsonHttpMessageConverter() {
        super(MediaType.APPLICATION_JSON);
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        return clazz == JsonObject.class || clazz == JsonArray.class || clazz == JsonNode.class;
    }

    // A body that is no JSON, or JSON of another kind than the class asked for, is a 400.
    @Override
    protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage) throws IOException {
        String text = new String(inputMessage.getBody().readAllBytes(), StandardCharsets.UTF_8);
        Object value;
        try {
            JsonNode node = Json.parse(text);
            value = (clazz == JsonObject.class) ? node.asObject() : (clazz == JsonArray.class) ? node.asArray() : node;
        }
        catch (JsonParseException | JsonLexerException | IllegalStateException | NumberFormatException ex) {
            throw new HttpMessageNotReadableException("JSON parse error: " + ex.getMessage(), ex, inputMessage);
        }
        if (value == null) {
            throw new HttpMessageNotReadableException("JSON parse error: null", inputMessage);
        }
        return value;
    }

    // tiny-json's values write themselves as JSON, in UTF-8 as RFC 8259 has it.
    @Override
    protected void writeInternal(Object value, HttpOutputMessage outputMessage) throws IOException {
        outputMessage.getBody().write(value.toString().getBytes(StandardCharsets.UTF_8));
    }
}
