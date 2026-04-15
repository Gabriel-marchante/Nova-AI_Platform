package org.acme.mcp.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.mcp.model.User;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class UserModelCache {

    @Inject
    EncryptionService encryptionService;

    private final ConcurrentHashMap<String, ChatModel> cache = new ConcurrentHashMap<>();

    public ChatModel getModel(UUID userId, String provider) {
        if (provider == null || provider.isBlank()) {
            provider = "gemini"; // default
        }
        
        String finalProvider = provider.toLowerCase();
        String cacheKey = userId.toString() + "_" + finalProvider;

        return cache.computeIfAbsent(cacheKey, key -> {
            User user = User.findById(userId);
            if (user == null) {
                throw new IllegalStateException("Usuario no encontrado.");
            }

            return switch (finalProvider) {
                case "chatgpt" -> {
                    if (user.openaiKeyEnc == null || user.openaiKeyEnc.isBlank()) {
                        throw new RuntimeException("No tienes configurada la clave de ChatGPT.");
                    }
                    String keyStr = encryptionService.decrypt(user.openaiKeyEnc);
                    yield OpenAiChatModel.builder()
                            .apiKey(keyStr)
                            .modelName("gpt-4o-mini")
                            .temperature(0.0)
                            .build();
                }
                case "claude" -> {
                    if (user.anthropicKeyEnc == null || user.anthropicKeyEnc.isBlank()) {
                        throw new RuntimeException("No tienes configurada la clave de Claude.");
                    }
                    String keyStr = encryptionService.decrypt(user.anthropicKeyEnc);
                    yield AnthropicChatModel.builder()
                            .apiKey(keyStr)
                            .modelName("claude-3-haiku-20240307")
                            .temperature(0.0)
                            .build();
                }
                default -> {
                    if (user.geminiKeyEnc == null || user.geminiKeyEnc.isBlank()) {
                        throw new RuntimeException("No tienes configurada la clave de Gemini.");
                    }
                    String keyStr = encryptionService.decrypt(user.geminiKeyEnc);
                    yield GoogleAiGeminiChatModel.builder()
                            .apiKey(keyStr)
                            .modelName("gemini-2.5-flash")
                            .temperature(0.0)
                            .build();
                }
            };
        });
    }

    public void invalidate(UUID userId) {
        // Remove all keys starting with userId
        String prefix = userId.toString() + "_";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
