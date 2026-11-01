package io.github.hansglorias.edtai.core.tools;

import java.math.BigDecimal;
import java.util.Map;

import io.github.hansglorias.edtai.core.chat.ToolUse;

/** Проверки аргументов перед побочными эффектами, без преобразования типов. */
final class ToolArguments {

    private final Map<?, ?> input;
    private final String prefix;

    ToolArguments(Map<?, ?> input) {
        this(input, "");
    }

    ToolArguments(Map<?, ?> input, String prefix) {
        this.input = input;
        this.prefix = prefix;
    }

    String string(String field) throws ToolException {
        if (!input.containsKey(field)) {
            throw error(field, "обязательно");
        }
        if (!(input.get(field) instanceof String value)) {
            throw error(field, "должно быть строкой");
        }
        return value;
    }

    String nonBlankString(String field) throws ToolException {
        String value = string(field);
        if (value.isBlank()) {
            throw error(field, "не должно быть пустым");
        }
        return value;
    }

    String optionalString(String field) throws ToolException {
        return input.containsKey(field) ? string(field) : "";
    }

    int positiveInteger(String field) throws ToolException {
        if (!input.containsKey(field)) {
            throw error(field, "обязательно");
        }
        if (input.get(field) instanceof Number number) {
            try {
                int value = new BigDecimal(number.toString()).intValueExact();
                if (value > 0) {
                    return value;
                }
            } catch (ArithmeticException | NumberFormatException e) {
                // Не усекаем дробную часть и не допускаем переполнения int.
            }
        }
        throw error(field, "должно быть целым числом от 1 до " + Integer.MAX_VALUE);
    }

    ToolException error(String field, String reason) {
        return new ToolException(ToolUse.Problem.BAD_ARGUMENTS, "Поле «" + prefix + field + "» " + reason);
    }
}
