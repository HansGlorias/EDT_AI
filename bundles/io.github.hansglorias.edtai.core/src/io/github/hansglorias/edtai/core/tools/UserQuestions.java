package io.github.hansglorias.edtai.core.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;

/** Структурированные уточнения. Ответ не даёт разрешения на изменение проекта. */
public final class UserQuestions {

    public record Option(String id, String text) { }
    public record Question(String id, String text, List<Option> options) {
        public Question { options = List.copyOf(options); }
    }
    public record Request(List<Question> questions) {
        public Request { questions = List.copyOf(questions); }
    }
    /** optionId пуст для своего ответа; text — выбранный вариант или введённый текст. */
    public record Answer(String id, String optionId, String text) { }

    private UserQuestions() { }

    public static Tool tool(Function<Request, CompletableFuture<List<Answer>>> ask) {
        return new Tool() {
            @Override public String name() { return "ask_user"; }
            @Override public boolean safeInPlan() { return true; }
            @Override public String description() {
                return "Уточняет у пользователя только необходимые для решения неоднозначности: 1–3 отдельных вопроса."
                        + " Для выбора дай 2–3 варианта с id и текстом; свободный ответ доступен всегда."
                        + " Не спрашивай очевидное и не используй для разрешения записи или утверждения плана."
                        + " После ответов продолжи задачу; в режиме План только составь план и жди его явного утверждения.";
            }
            @Override public Map<String, Object> inputSchema() {
                Map<String, Object> string = Map.of("type", "string", "minLength", 1, "maxLength", 1000);
                Map<String, Object> id = Map.of("type", "string", "pattern", "^[a-zA-Z][a-zA-Z0-9_-]{0,39}$");
                Map<String, Object> option = object(Map.of("id", id, "text", string), List.of("id", "text"));
                Map<String, Object> question = object(Map.of("id", id, "text", string, "options",
                        Map.of("type", "array", "minItems", 2, "maxItems", 3, "items", option)), List.of("id", "text"));
                return object(Map.of("questions", Map.of("type", "array", "minItems", 1, "maxItems", 3,
                        "items", question)), List.of("questions"));
            }
            @Override public String summary(Map<String, Object> input) { return "Уточняет задачу у пользователя"; }
            @Override public Result call(Map<String, Object> input) throws ToolException {
                Request request = parse(input); // Вся форма проверена до обращения к UI.
                CompletableFuture<List<Answer>> pending = ask.apply(request);
                try {
                    List<Answer> answers = pending.get(); // Только поток агента, HTTP-ответ уже закрыт.
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                    String json = result(request, answers);
                    List<String> decisions = new ArrayList<>();
                    for (int i = 0; i < answers.size(); i++) {
                        decisions.add(request.questions().get(i).text() + " — " + answers.get(i).text());
                    }
                    String outcome = String.join("; ", decisions);
                    return new Result(json, outcome);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                } catch (ExecutionException e) {
                    throw new CancellationException("Уточнение отменено");
                } finally {
                    pending.cancel(true);
                }
            }
        };
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    public static Request parse(Map<String, Object> input) throws ToolException {
        keys(input, Set.of("questions"));
        if (!(input.get("questions") instanceof List<?> list) || list.isEmpty() || list.size() > 3) {
            throw invalid("questions: нужен массив из 1–3 вопросов");
        }
        List<Question> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object value : list) {
            Map<?, ?> item = map(value);
            keys(item, Set.of("id", "text", "options"));
            String id = id(item);
            if (!ids.add(id)) throw invalid("повтор id вопроса: " + id);
            String text = text(item, "text", 1000);
            List<Option> options = new ArrayList<>();
            if (item.containsKey("options")) {
                if (!(item.get("options") instanceof List<?> choices) || choices.size() < 2 || choices.size() > 3) {
                    throw invalid("options: нужны 2–3 варианта или отсутствие поля");
                }
                Set<String> optionIds = new HashSet<>();
                for (Object choice : choices) {
                    Map<?, ?> option = map(choice);
                    keys(option, Set.of("id", "text"));
                    String optionId = id(option);
                    if (!optionIds.add(optionId)) throw invalid("повтор id варианта: " + optionId);
                    options.add(new Option(optionId, text(option, "text", 1000)));
                }
            }
            questions.add(new Question(id, text, options));
        }
        return new Request(questions);
    }

    /** Проверяет и сериализует ровно один ответ на каждый вопрос в исходном порядке. */
    public static String result(Request request, List<Answer> answers) throws ToolException {
        if (answers == null || answers.size() != request.questions().size()) throw invalid("нужны ответы на все вопросы");
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            Answer answer = answers.get(i);
            Question question = request.questions().get(i);
            if (answer == null || !question.id().equals(answer.id()) || answer.text() == null
                    || answer.text().isBlank() || answer.text().length() > 4000 || answer.optionId() == null) {
                throw invalid("неверный ответ на вопрос " + question.id());
            }
            if (!answer.optionId().isEmpty() && question.options().stream().noneMatch(option ->
                    option.id().equals(answer.optionId()) && option.text().equals(answer.text()))) {
                throw invalid("неизвестный вариант для " + question.id());
            }
            values.add(Json.object("id", answer.id(), "option_id", answer.optionId(), "text", answer.text()));
        }
        return Json.write(Json.object("answers", values));
    }

    private static Map<?, ?> map(Object value) throws ToolException {
        if (value instanceof Map<?, ?> map) return map;
        throw invalid("ожидался объект вопроса или варианта");
    }
    private static void keys(Map<?, ?> map, Set<String> allowed) throws ToolException {
        if (!allowed.containsAll(map.keySet())) throw invalid("неизвестное поле");
    }
    private static String id(Map<?, ?> map) throws ToolException {
        String value = text(map, "id", 40);
        if (!value.matches("[a-zA-Z][a-zA-Z0-9_-]{0,39}")) throw invalid("id: нужны латинские буквы, цифры, _ или -");
        return value;
    }
    private static String text(Map<?, ?> map, String field, int max) throws ToolException {
        if (!(map.get(field) instanceof String value) || value.isBlank() || value.length() > max) {
            throw invalid(field + ": нужна непустая строка до " + max + " символов");
        }
        return value;
    }
    private static ToolException invalid(String message) {
        return new ToolException(ToolUse.Problem.BAD_ARGUMENTS, "ask_user: " + message);
    }
}
