package io.github.hansglorias.edtai.core.json;

/** Некорректный JSON или значение, которое нельзя записать в JSON. */
public class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
