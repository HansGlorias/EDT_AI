package io.github.hansglorias.edtai.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ModelChoicesTest {

    @Test
    void currentRecentThenServer() {
        assertEquals(List.of("gpt-4o", "qwen3-32b", "local", "Deepseek-r1", "llama"),
                ModelChoices.merge("gpt-4o", List.of("qwen3-32b", "gpt-4o", "local", " "), List.of("llama", "qwen3-32b", "Deepseek-r1")));
        assertEquals(List.of("a"), ModelChoices.merge("", List.of(), List.of("a")));
    }

    @Test
    void recentAreRememberedAndStored() {
        List<String> recent = List.of("1", "2", "3", "4", "5", "6", "7", "8");
        assertEquals(List.of("9", "1", "2", "3", "4", "5", "6", "7"), ModelChoices.remember(recent, "9"));
        assertEquals(List.of("3", "1", "2"), ModelChoices.remember(List.of("1", "2", "3"), "3"));
        assertEquals(List.of("a", "b"), ModelChoices.parse(ModelChoices.format(List.of("a", "b"))));
        assertEquals(List.of(), ModelChoices.parse(null));
    }

    @Test
    void typedNameFindsModel() {
        List<String> choices = List.of("qwen3-32b", "qwen3-coder", "deepseek-r1", "Qwen");
        assertEquals(Optional.of("deepseek-r1"), ModelChoices.find("DEEP", choices));
        assertEquals(Optional.of("Qwen"), ModelChoices.find("Qwen", choices), "точное имя — даже если подходят и другие");
        assertEquals(Optional.empty(), ModelChoices.find("qwen3", choices));
        assertEquals(Optional.empty(), ModelChoices.find(" ", choices));
    }

    @Test
    void shortLabel() {
        assertEquals("claude-sonnet", ModelChoices.label("anthropic/claude-sonnet", 28));
        assertEquals("very-long-mod…", ModelChoices.label("very-long-model-name", 14));
        assertEquals("qwen3", ModelChoices.label(" qwen3 ", 28));
    }

    @Test
    void settingsWithOtherModel() {
        AiSettings settings = new AiSettings(Protocol.ANTHROPIC, "https://llm.example.invalid", "a", 100, "п", 5_000, false, true, "",
                List.of(), 64_000);
        AiSettings other = settings.withModel("b");
        assertEquals("b", other.model());
        assertEquals(new AiSettings(Protocol.ANTHROPIC, "https://llm.example.invalid", "b", 100, "п", 5_000, false, true, "", List.of(),
                64_000), other);
    }
}
