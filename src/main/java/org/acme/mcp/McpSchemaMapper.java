package org.acme.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class McpSchemaMapper {

    public Map<String, Integer> collectToolOccurrences(Iterable<McpSession> connectedSessions) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (McpSession session : connectedSessions) {
            for (ToolSpecification tool : session.tools()) {
                occurrences.merge(tool.name(), 1, Integer::sum);
            }
        }
        return occurrences;
    }

    public List<Map<String, Object>> toApiTools(McpSession session, Map<String, Integer> occurrences) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSpecification tool : session.tools()) {
            ToolSpecification exposed = applyAlias(session.endpointUrl(), tool, occurrences);
            tools.add(Map.of(
                    "name", exposed.name(),
                    "description", exposed.description() == null ? "" : exposed.description(),
                    "inputSchema", toApiSchema(exposed.parameters())
            ));
        }
        return tools;
    }

    public ToolSpecification applyAlias(String endpointUrl, ToolSpecification tool, Map<String, Integer> occurrences) {
        String exposedName = exposedToolName(endpointUrl, tool.name(), occurrences);
        if (exposedName.equals(tool.name())) {
            return tool;
        }
        return ToolSpecification.builder()
                .name(exposedName)
                .description(tool.description())
                .parameters(tool.parameters())
                .build();
    }

    public String resolveExecutionToolName(McpSession session, String requestedToolName, Map<String, Integer> occurrences) {
        for (ToolSpecification tool : session.tools()) {
            if (tool.name().equals(requestedToolName)) {
                return requestedToolName;
            }
            String exposedName = exposedToolName(session.endpointUrl(), tool.name(), occurrences);
            if (exposedName.equals(requestedToolName)) {
                return tool.name();
            }
        }
        return requestedToolName;
    }

    public String exposedToolName(String endpointUrl, String originalToolName, Map<String, Integer> occurrences) {
        if (occurrences.getOrDefault(originalToolName, 0) <= 1) {
            return originalToolName;
        }
        return buildToolAlias(endpointUrl, originalToolName);
    }

    private String buildToolAlias(String endpointUrl, String toolName) {
        URI uri = URI.create(endpointUrl);
        String host = uri.getHost() == null ? "server" : uri.getHost().replaceAll("[^a-zA-Z0-9]", "_");
        int port = uri.getPort();
        String suffix = port > 0 ? host + "_" + port : host;
        return suffix + "__" + toolName;
    }

    private Map<String, Object> toApiSchema(JsonObjectSchema schema) {
        if (schema == null) {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }

        Map<String, Object> apiSchema = new LinkedHashMap<>();
        apiSchema.put("type", "object");
        apiSchema.put("properties", toApiProperties(schema.properties()));
        apiSchema.put("required", schema.required() == null ? List.of() : schema.required());
        if (schema.description() != null && !schema.description().isBlank()) {
            apiSchema.put("description", schema.description());
        }
        if (schema.additionalProperties() != null) {
            apiSchema.put("additionalProperties", schema.additionalProperties());
        }
        if (schema.definitions() != null && !schema.definitions().isEmpty()) {
            apiSchema.put("definitions", toApiProperties(schema.definitions()));
        }
        return apiSchema;
    }

    private Map<String, Object> toApiProperties(Map<String, JsonSchemaElement> properties) {
        if (properties == null || properties.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> serialized = new LinkedHashMap<>();
        for (Map.Entry<String, JsonSchemaElement> entry : properties.entrySet()) {
            serialized.put(entry.getKey(), toApiSchemaElement(entry.getValue()));
        }
        return serialized;
    }

    private Object toApiSchemaElement(JsonSchemaElement element) {
        if (element == null) {
            return Map.of();
        }
        if (element instanceof JsonObjectSchema objectSchema) {
            return toApiSchema(objectSchema);
        }
        if (element instanceof JsonStringSchema stringSchema) {
            return scalarSchema("string", stringSchema.description());
        }
        if (element instanceof JsonIntegerSchema integerSchema) {
            return scalarSchema("integer", integerSchema.description());
        }
        if (element instanceof JsonNumberSchema numberSchema) {
            return scalarSchema("number", numberSchema.description());
        }
        if (element instanceof JsonBooleanSchema booleanSchema) {
            return scalarSchema("boolean", booleanSchema.description());
        }
        if (element instanceof JsonEnumSchema enumSchema) {
            Map<String, Object> schema = scalarSchema("string", enumSchema.description());
            schema.put("enum", enumSchema.enumValues());
            return schema;
        }
        if (element instanceof JsonArraySchema arraySchema) {
            Map<String, Object> schema = scalarSchema("array", arraySchema.description());
            if (arraySchema.items() != null) {
                schema.put("items", toApiSchemaElement(arraySchema.items()));
            }
            return schema;
        }
        if (element instanceof JsonReferenceSchema referenceSchema) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("$ref", referenceSchema.reference());
            if (referenceSchema.description() != null && !referenceSchema.description().isBlank()) {
                schema.put("description", referenceSchema.description());
            }
            return schema;
        }
        if (element instanceof JsonAnyOfSchema anyOfSchema) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("anyOf", anyOfSchema.anyOf() == null ? List.of() : anyOfSchema.anyOf().stream().map(this::toApiSchemaElement).toList());
            if (anyOfSchema.description() != null && !anyOfSchema.description().isBlank()) {
                schema.put("description", anyOfSchema.description());
            }
            return schema;
        }
        if (element instanceof JsonRawSchema rawSchema) {
            Map<String, Object> schema = new LinkedHashMap<>();
            if (rawSchema.description() != null && !rawSchema.description().isBlank()) {
                schema.put("description", rawSchema.description());
            }
            schema.put("raw", rawSchema.schema());
            return schema;
        }
        if (element instanceof JsonNullSchema nullSchema) {
            return scalarSchema("null", nullSchema.description());
        }
        return Map.of("description", element.description() == null ? "" : element.description());
    }

    private Map<String, Object> scalarSchema(String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        if (description != null && !description.isBlank()) {
            schema.put("description", description);
        }
        return schema;
    }
}
