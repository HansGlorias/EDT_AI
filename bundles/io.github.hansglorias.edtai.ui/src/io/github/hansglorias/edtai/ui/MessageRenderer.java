package io.github.hansglorias.edtai.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Set;

import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.FontRegistry;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;

import io.github.hansglorias.edtai.core.chat.AnswerParts;
import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.markdown.CodeBlock;
import io.github.hansglorias.edtai.core.markdown.Kind;
import io.github.hansglorias.edtai.core.markdown.Markdown;
import io.github.hansglorias.edtai.core.markdown.RichText;
import io.github.hansglorias.edtai.core.markdown.Segment;

/**
 * Текст и оформление одного сообщения в ленте: заголовок с именем, временем
 * и действиями, тело — Markdown для ответа модели, обычный текст для
 * остального.
 */
final class MessageRenderer {

    static final String COPY = "Копировать";
    static final String RETRY = "Повторить";
    static final String EDIT = "Изменить";
    static final String CHANGES = "Изменения";
    static final String REVERT = "Откатить";
    static final String INSERT = "Вставить в редактор";
    static final String CONTEXT = "Контекст: ";
    static final String FILES = "Вложения: ";
    static final String TOOL = "→ ";
    static final String CHANGED = "Изменено: ";
    static final String SHOW_CALLS = "показать";
    static final String HIDE_CALLS = "свернуть";
    /** Больше стольких вызовов подряд — сворачиваются в одну строку. */
    static final int COLLAPSE = 3;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final List<String> CODE_ACTIONS = List.of(COPY, INSERT);

    /** Действия у сообщения в строке заголовка. */
    enum Action {
        COPY(MessageRenderer.COPY), RETRY(MessageRenderer.RETRY), EDIT(MessageRenderer.EDIT), CHANGES(MessageRenderer.CHANGES),
        REVERT(MessageRenderer.REVERT);

        final String label;

        Action(String label) {
            this.label = label;
        }
    }

    /** Что делать по ссылкам в ленте. */
    interface Callbacks {
        void copy(String text);

        void insert(String code);

        void retry();

        void edit(int index);

        void open(String url);

        /** Открыть файл фрагмента на его строках. */
        void openContext(ContextItem item);

        /** Показать дифф правок модели в этом ответе. */
        void showChanges(ChatMessage answer);

        /** Вернуть модули, как были до правок модели в этом ответе. */
        void revertChanges(ChatMessage answer);

        /** Файлы, изменённые в этом ответе: пути в проекте; пусто — ничего. */
        List<String> changedFiles(ChatMessage answer);

        /** Сравнение одного изменённого файла. */
        void showChange(ChatMessage answer, String path);

        /** Развёрнута ли свёрнутая группа вызовов номер {@code group} в ответе. */
        boolean expanded(ChatMessage answer, int group);

        /** «показать» / «свернуть» у группы вызовов. */
        void toggle(ChatMessage answer, int group);
    }

    private final StyledText history;
    private final Callbacks callbacks;
    private Color codeBackground;
    private RGB codeBackgroundFrom;
    private Color userBackground;
    private RGB userBackgroundFrom;

    MessageRenderer(StyledText history, Callbacks callbacks) {
        this.history = history;
        this.callbacks = callbacks;
    }

    /**
     * Дописывает сообщение в {@code text}, оформление — в {@code styles}
     * (по возрастанию, без пересечений), диапазоны блоков кода для фона — в
     * {@code codeRanges}. Смещения — от начала ленты: {@code offset} — где в
     * ленте начинается {@code text}.
     */
    void render(ChatMessage message, int index, boolean separator, Set<Action> actions, int offset, StringBuilder text,
            List<StyleRange> styles, List<int[]> codeRanges) {
        if (separator) {
            text.append("\n\n");
        }
        header(message, index, actions, offset, text, styles);
        context(message, offset, text, styles);
        attachments(message, offset, text, styles);
        String body = message.text();
        if (message.role() == ChatRole.ASSISTANT) {
            // Текст и вызовы по порядку, как шёл ответ: видно, что модель делает между абзацами.
            List<AnswerParts.Part> parts = AnswerParts.split(body, message.tools());
            boolean afterTools = false;
            int group = 0;
            for (int i = 0; i < parts.size(); i++) {
                AnswerParts.Part part = parts.get(i);
                if (i > 0) {
                    text.append(afterTools ? "\n" : "\n\n");
                }
                if (part.isText()) {
                    RichText rich = Markdown.render(part.text(), CODE_ACTIONS);
                    markdown(rich, offset + text.length(), styles, codeRanges);
                    text.append(rich.text());
                } else {
                    tools(message, group++, part.tools(), offset, text, styles);
                }
                afterTools = !part.isText();
            }
            if (parts.stream().noneMatch(AnswerParts.Part::isText)) {
                String placeholder = switch (message.status()) {
                    case STREAMING -> "…";
                    case STOPPED -> "(ответ не получен)";
                    case DONE -> "(пустой ответ)";
                };
                if (!parts.isEmpty()) {
                    text.append('\n');
                }
                styles.add(style(offset + text.length(), placeholder.length(), metaColor(), SWT.ITALIC));
                text.append(placeholder);
            }
            changedFiles(message, offset, text, styles);
        } else {
            if (message.role() == ChatRole.ERROR) {
                styles.add(style(offset + text.length(), body.length(), errorColor(), SWT.NORMAL));
            } else if (message.role() == ChatRole.SUMMARY) {
                styles.add(style(offset + text.length(), body.length(), metaColor(), SWT.ITALIC));
            }
            text.append(body);
        }
    }

    private void header(ChatMessage message, int index, Set<Action> actions, int offset, StringBuilder text, List<StyleRange> styles) {
        String name;
        Color nameColor;
        switch (message.role()) {
            case USER -> {
                name = "Вы";
                nameColor = null;
            }
            case ASSISTANT -> {
                name = "Ассистент";
                nameColor = color(JFacePreferences.COUNTER_COLOR, SWT.COLOR_DARK_BLUE);
            }
            case SUMMARY -> {
                name = "Краткое содержание диалога выше";
                nameColor = metaColor();
            }
            default -> {
                name = "Ошибка";
                nameColor = errorColor();
            }
        }
        StyleRange nameStyle = style(offset + text.length(), name.length(), nameColor, SWT.NORMAL);
        nameStyle.font = nameFont();
        styles.add(nameStyle);
        text.append(name);

        String meta = "  " + TIME.format(message.time()) + switch (message.status()) {
            case STREAMING -> " · пишет…";
            case STOPPED -> message.role() == ChatRole.ASSISTANT ? " · остановлено" : "";
            case DONE -> "";
        };
        styles.add(style(offset + text.length(), meta.length(), metaColor(), SWT.NORMAL));
        text.append(meta);

        for (Action action : Action.values()) {
            if (actions.contains(action)) {
                text.append("   ");
                Runnable run = switch (action) {
                    case COPY -> () -> callbacks.copy(message.text());
                    case RETRY -> callbacks::retry;
                    case EDIT -> () -> callbacks.edit(index);
                    case CHANGES -> () -> callbacks.showChanges(message);
                    case REVERT -> () -> callbacks.revertChanges(message);
                };
                StyleRange link = new StyleRange(offset + text.length(), action.label.length(), linkColor(), null);
                link.data = run;
                styles.add(link);
                text.append(action.label);
            }
        }
        text.append('\n');
    }

    /**
     * Строки о вызванных инструментах: «→ Читает модуль Х — строки 1–120 из
     * 120», без перевода строки в конце. Больше {@link #COLLAPSE} подряд —
     * одна строка «Вызовы инструментов: N» со ссылкой «показать» (пока
     * ответ идёт — и последний вызов под ней).
     */
    private void tools(ChatMessage message, int group, List<ToolUse> uses, int offset, StringBuilder text, List<StyleRange> styles) {
        List<ToolUse> shown = uses;
        if (uses.size() > COLLAPSE) {
            boolean expanded = callbacks.expanded(message, group);
            long failed = uses.stream().filter(use -> use.status() == ToolUse.Status.FAILED).count();
            String summary = TOOL + "Вызовы инструментов: " + uses.size() + (failed > 0 ? ", с ошибкой: " + failed : "");
            styles.add(style(offset + text.length(), summary.length(), failed > 0 ? errorColor() : metaColor(), SWT.NORMAL));
            text.append(summary).append("   ");
            String label = expanded ? HIDE_CALLS : SHOW_CALLS;
            StyleRange link = new StyleRange(offset + text.length(), label.length(), linkColor(), null);
            link.data = (Runnable) () -> callbacks.toggle(message, group);
            styles.add(link);
            text.append(label);
            if (!expanded && message.status() != ChatMessage.Status.STREAMING) {
                return;
            }
            text.append('\n');
            shown = expanded ? uses : uses.subList(uses.size() - 1, uses.size());
        }
        uses = shown;
        for (int i = 0; i < uses.size(); i++) {
            ToolUse use = uses.get(i);
            String line = TOOL + use.label();
            styles.add(style(offset + text.length(), line.length(),
                    use.status() == ToolUse.Status.FAILED ? errorColor() : metaColor(), SWT.NORMAL));
            text.append(line);
            if (i < uses.size() - 1) {
                text.append('\n');
            }
        }
    }

    /** «Изменено: ФормаСписка/Form.form, ФормаСписка/Module.bsl» — каждый файл открывает сравнение. */
    private void changedFiles(ChatMessage message, int offset, StringBuilder text, List<StyleRange> styles) {
        List<String> files = callbacks.changedFiles(message);
        if (files.isEmpty()) {
            return;
        }
        text.append("\n\n");
        styles.add(style(offset + text.length(), CHANGED.length(), metaColor(), SWT.NORMAL));
        text.append(CHANGED);
        for (int i = 0; i < files.size(); i++) {
            String path = files.get(i);
            if (i > 0) {
                text.append(", ");
            }
            String label = shortName(path);
            StyleRange link = new StyleRange(offset + text.length(), label.length(), linkColor(), null);
            link.data = (Runnable) () -> callbacks.showChange(message, path);
            styles.add(link);
            text.append(label);
        }
    }

    /** Имя файла с каталогом над ним: «ФормаСписка/Form.form». */
    static String shortName(String path) {
        String[] parts = path.split("/");
        return parts.length < 2 ? path : parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    /** Строка «Контекст:» с подписями фрагментов-ссылками. */
    private void context(ChatMessage message, int offset, StringBuilder text, List<StyleRange> styles) {
        if (message.context().isEmpty()) {
            return;
        }
        styles.add(style(offset + text.length(), CONTEXT.length(), metaColor(), SWT.NORMAL));
        text.append(CONTEXT);
        for (int i = 0; i < message.context().size(); i++) {
            ContextItem item = message.context().get(i);
            if (i > 0) {
                text.append(", ");
            }
            String label = item.label();
            StyleRange link = new StyleRange(offset + text.length(), label.length(), linkColor(), null);
            link.data = (Runnable) () -> callbacks.openContext(item);
            styles.add(link);
            text.append(label);
        }
        text.append('\n');
    }

    /** Строка «Вложения:» с подписями файлов и скриншотов. */
    private void attachments(ChatMessage message, int offset, StringBuilder text, List<StyleRange> styles) {
        if (message.attachments().isEmpty()) {
            return;
        }
        String line = FILES + message.attachments().stream().map(Attachment::label).collect(Collectors.joining("; "));
        styles.add(style(offset + text.length(), line.length(), metaColor(), SWT.NORMAL));
        text.append(line).append('\n');
    }

    private void markdown(RichText rich, int offset, List<StyleRange> styles, List<int[]> codeRanges) {
        for (CodeBlock block : rich.codeBlocks()) {
            codeRanges.add(new int[] { offset + block.start(), offset + block.end() });
        }
        for (Segment segment : rich.segments()) {
            styles.add(style(segment, offset, rich.codeBlocks()));
        }
    }

    private StyleRange style(Segment segment, int offset, List<CodeBlock> blocks) {
        StyleRange range = new StyleRange();
        range.start = offset + segment.start();
        range.length = segment.length();
        boolean bold = segment.has(Kind.BOLD) || segment.headingLevel() >= 3;
        boolean italic = segment.has(Kind.ITALIC) || segment.has(Kind.QUOTE);
        if (segment.monospace()) {
            range.font = monospace(bold, italic);
        } else if (segment.headingLevel() == 1 || segment.headingLevel() == 2) {
            range.font = JFaceResources.getHeaderFont();
        } else {
            range.fontStyle = (bold ? SWT.BOLD : SWT.NORMAL) | (italic ? SWT.ITALIC : SWT.NORMAL);
        }
        if (segment.has(Kind.CODE)) {
            range.background = codeBackground();
        }
        range.strikeout = segment.has(Kind.STRIKE);
        if (segment.has(Kind.MARKER) || segment.has(Kind.QUOTE) || segment.has(Kind.CODE_HEADER)) {
            range.foreground = metaColor();
        }
        if (segment.link() != null) {
            String url = segment.link();
            range.foreground = linkColor();
            range.underline = true;
            range.data = (Runnable) () -> callbacks.open(url);
        }
        if (segment.action() != null) {
            int colon = segment.action().lastIndexOf(':');
            String label = segment.action().substring(0, colon);
            String code = blocks.get(Integer.parseInt(segment.action().substring(colon + 1))).code();
            range.foreground = linkColor();
            Runnable run = INSERT.equals(label) ? () -> callbacks.insert(code) : () -> callbacks.copy(code);
            range.data = run;
        }
        return range;
    }

    private static StyleRange style(int start, int length, Color foreground, int fontStyle) {
        return new StyleRange(start, length, foreground, null, fontStyle);
    }

    private static final String NAME_FONT = "io.github.hansglorias.edtai.ui.nameFont";

    /** Имя автора сообщения: жирный шрифт на пункт крупнее текста — сообщения легче различать. */
    private static Font nameFont() {
        FontRegistry registry = JFaceResources.getFontRegistry();
        if (!registry.hasValueFor(NAME_FONT)) {
            FontData[] data = registry.getFontData(JFaceResources.DEFAULT_FONT);
            FontData[] larger = new FontData[data.length];
            for (int i = 0; i < data.length; i++) {
                larger[i] = new FontData(data[i].getName(), data[i].getHeight() + 1, data[i].getStyle());
            }
            registry.put(NAME_FONT, larger);
        }
        return registry.getBold(NAME_FONT);
    }

    private static Font monospace(boolean bold, boolean italic) {
        if (bold) {
            return JFaceResources.getFontRegistry().getBold(JFaceResources.TEXT_FONT);
        }
        if (italic) {
            return JFaceResources.getFontRegistry().getItalic(JFaceResources.TEXT_FONT);
        }
        return JFaceResources.getTextFont();
    }

    /** Фон кода: цвет текста, чуть подмешанный к фону ленты. Читается и в светлой, и в тёмной теме. */
    Color codeBackground() {
        RGB background = history.getBackground().getRGB();
        RGB foreground = history.getForeground().getRGB();
        if (codeBackground == null || !background.equals(codeBackgroundFrom)) {
            double share = 0.07;
            codeBackground = new Color(mix(background.red, foreground.red, share), mix(background.green, foreground.green, share),
                    mix(background.blue, foreground.blue, share));
            codeBackgroundFrom = background;
        }
        return codeBackground;
    }

    /** Фон вопросов пользователя: лёгкий оттенок цвета выделения — вопросы видно среди ответов. */
    Color userBackground() {
        RGB background = history.getBackground().getRGB();
        if (userBackground == null || !background.equals(userBackgroundFrom)) {
            RGB accent = history.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION).getRGB();
            double share = 0.12;
            userBackground = new Color(mix(background.red, accent.red, share), mix(background.green, accent.green, share),
                    mix(background.blue, accent.blue, share));
            userBackgroundFrom = background;
        }
        return userBackground;
    }

    private static int mix(int from, int to, double share) {
        return (int) Math.round(from + (to - from) * share);
    }

    private Color color(String key, int fallback) {
        Color color = JFaceResources.getColorRegistry().get(key);
        return color != null ? color : history.getDisplay().getSystemColor(fallback);
    }

    private Color errorColor() {
        return color(JFacePreferences.ERROR_COLOR, SWT.COLOR_RED);
    }

    private Color metaColor() {
        return color(JFacePreferences.QUALIFIER_COLOR, SWT.COLOR_DARK_GRAY);
    }

    private Color linkColor() {
        return color(JFacePreferences.HYPERLINK_COLOR, SWT.COLOR_LINK_FOREGROUND);
    }
}
