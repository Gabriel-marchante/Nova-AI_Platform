package org.acme.mcp.model;

import java.util.Map;

public record JsonRpcResponse(
        String jsonrpc,
        Object id,
        Map<String, Object> result,
        Map<String, Object> error
) {}