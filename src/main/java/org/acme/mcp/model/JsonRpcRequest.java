package org.acme.mcp.model;

import java.util.Map;

public record JsonRpcRequest(
        String jsonrpc,
        Object id,
        String method,
        Map<String, Object> params
) {
    // Constructor auxiliar para que no tengamos que escribir "2.0" todo el rato
    public JsonRpcRequest(Object id, String method, Map<String, Object> params) {
        this("2.0", id, method, params);
    }
}