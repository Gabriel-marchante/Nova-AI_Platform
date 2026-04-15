package org.acme.mcp.dto;

public class AuthResponse {
    public String token;
    public String error;

    public AuthResponse(String token, String error) {
        this.token = token;
        this.error = error;
    }

    public static AuthResponse success(String token) {
        return new AuthResponse(token, null);
    }

    public static AuthResponse error(String error) {
        return new AuthResponse(null, error);
    }
}
