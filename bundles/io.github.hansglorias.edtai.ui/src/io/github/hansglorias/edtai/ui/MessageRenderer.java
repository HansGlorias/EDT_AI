package io.github.hansglorias.edtai.ui;

import java.io.ByteArrayInputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.PaintObjectEvent;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.GlyphMetrics;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.RGB;

import io.github.hansglorias.edtai.core.chat.AnswerParts;
import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.review.Finding;
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
 * Текст и оформление одного сообщения в ленте, как в мессенджерах: без
 * подписи автора, вопрос пользователя — пузырём справа (его рисует
 * {@link ChatView}), ответ — слева во всю ширину. Под сообщением строка с
 * временем и действиями-значками. Тело — Markdown для ответа модели, обычный
 * текст для остального.
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
    static final String SAVE_FILE = "Сохранить…";
    static final String OPEN_FILE = "Открыть";
    static final String TOOL = "→ ";
    static final String CHANGED = "Изменено: ";
    static final String SHOW_CALLS = "показать";
    static final String HIDE_CALLS = "свернуть";
    /** Больше стольких вызовов подряд — сворачиваются в одну строку. */
    static final int COLLAPSE = 3;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final List<String> CODE_ACTIONS = List.of(COPY, INSERT);

    /** Действия в строке под сообщением; со значком — показываются значком, подпись — во всплывающей подсказке. */
    enum Action {
        COPY(MessageRenderer.COPY, Icons.COPY), RETRY(MessageRenderer.RETRY, Icons.RETRY), EDIT(MessageRenderer.EDIT, Icons.EDIT),
        CHANGES(MessageRenderer.CHANGES, null), REVERT(MessageRenderer.REVERT, null);

        final String label;
        final String icon;

        Action(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    /**
     * Где в ленте части сообщения: пузырь вопроса пользователя
     * {@code [bubbleStart, bubbleEnd)} (-1 — не вопрос) и начало строки под
     * сообщением с временем и действиями.
     */
    record Placement(int bubbleStart, int bubbleEnd, int footerStart) {
        boolean bubble() {
            return bubbleStart >= 0;
        }
    }

    /** Значок действия в строке под сообщением: щелчок — действие, подсказка — подпись. */
    record ActionIcon(Image image, String label, Runnable action) implements Runnable {
        @Override
        public void run() {
            action.run();
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

        /** Открыть вложение программой системы: картинку целиком, файл из ответа. */
        void openAttachment(Attachment file);

        /** Сохранить файл из ответа модели. */
        void saveAttachment(Attachment file);

        /** Открыть модуль находки ревью на её строке. */
        void openFinding(Finding finding);

        /** Снять маркеры ревью в редакторе. */
        void clearReviewMarkers();
    }

    static final String CLEAR_MARKERS = "убрать отметки";
    static final String SUMMARY = "Краткое содержание диалога выше";
    private static final String BULLET = "●";

    /** Миниатюра картинки в ленте: место под неё — символ с {@link GlyphMetrics}, рисует её вид. */
    record Thumbnail(Image image, int width, int height, Runnable open) implements Runnable {
        @Override
        public void run() {
            open.run();
        }
    }

    /** Значок файла из ответа: щелчок — открыть. */
    record FileIcon(Image image, Runnable open) implements Runnable {
        @Override
        public void run() {
            open.run();
        }
    }

    private static final int ICON = 16;
    /** Промежуток после значка действия. */
    private static final int ICON_GAP = 8;

    /** Предел миниатюры: крупнее — уменьшается с сохранением пропорций. */
    static final int THUMB_WIDTH = 240;
    static final int THUMB_HEIGHT = 140;
    private static final int THUMB_GAP = 8;
    /** Символ-заглушка под миниатюру. */
    static final char OBJECT = '\uFFFC';
    private final Map<Attachment, Thumbnail> thumbnails = new HashMap<>();

    private final StyledText history;
    private final Callbacks callbacks;
    private Color codeBackground;
    private RGB codeBackgroundFrom;

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
    Placement render(ChatMessage message, int index, boolean separator, Set<Action> actions, int offset, StringBuilder text,
            List<StyleRange> styles, List<int[]> codeRanges, String pending) {
        if (separator) {
            text.append("\n\n");
        }
        int bubbleStart = message.role() == ChatRole.USER ? offset + text.length() : -1;
        if (message.role() == ChatRole.SUMMARY) {
            styles.add(style(offset + text.length(), SUMMARY.length(), metaColor(), SWT.BOLD));
            text.append(SUMMARY).append('\n');
        }
        context(message, offset, text, styles);
        if (message.role() != ChatRole.ASSISTANT) {
            attachments(message, offset, text, styles);
        }
        String body = message.text();
        if (message.role() == ChatRole.ASSISTANT) {
            // Текст и вызовы по порядку, как шёл ответ: видно, что модель делает между абзацами.
            List<AnswerParts.Part> parts = AnswerParts.split(body.isBlank() ? "" : body, message.tools());
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
                    case STREAMING -> pending;
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
            review(message, offset, text, styles, codeRanges);
            files(message, offset, text, styles, codeRanges);
        } else {
            if (message.role() == ChatRole.ERROR) {
                styles.add(style(offset + text.length(), body.length(), errorColor(), SWT.NORMAL));
            } else if (message.role() == ChatRole.SUMMARY) {
                styles.add(style(offset + text.length(), body.length(), metaColor(), SWT.ITALIC));
            }
            text.append(body);
        }
        int bubbleEnd = bubbleStart < 0 ? -1 : offset + text.length();
        text.append('\n');
        int footerStart = offset + text.length();
        footer(message, index, actions, offset, text, styles);
        return new Placement(bubbleStart, bubbleEnd, footerStart);
    }

    /**
     * Строка под сообщением: у ответа — значки действий, ссылки на правки и
     * время; у вопроса — время и значок «Изменить» (строка прижата вправо,
     * под пузырь). Без перевода строки в конце.
     */
    private void footer(ChatMessage message, int index, Set<Action> actions, int offset, StringBuilder text, List<StyleRange> styles) {
        String meta = TIME.format(message.time()) + switch (message.status()) {
            case STREAMING -> message.text().isBlank() ? "" : " · пишет…";
            case STOPPED -> message.role() == ChatRole.ASSISTANT ? " · остановлено" : "";
            case DONE -> "";
        };
        boolean user = message.role() == ChatRole.USER;
        if (user) {
            styles.add(style(offset + text.length(), meta.length(), metaColor(), SWT.NORMAL));
            text.append(meta).append(' ');
        }
        boolean links = false;
        for (Action action : Action.values()) {
            if (!actions.contains(action)) {
                continue;
            }
            Runnable run = switch (action) {
                case COPY -> () -> callbacks.copy(message.text());
                case RETRY -> callbacks::retry;
                case EDIT -> () -> callbacks.edit(index);
                case CHANGES -> () -> callbacks.showChanges(message);
                case REVERT -> () -> callbacks.revertChanges(message);
            };
            if (action.icon != null) {
                StyleRange place = new StyleRange();
                place.start = offset + text.length();
                place.length = 1;
                place.metrics = new GlyphMetrics(ICON - 3, 3, ICON + ICON_GAP);
                place.data = new ActionIcon(Icons.image(action.icon), action.label, run);
                styles.add(place);
                text.append(OBJECT);
            } else {
                text.append(links ? "   " : "  ");
                link(text, styles, offset, action.label, run);
                links = true;
            }
        }
        if (!user) {
            char last = text.charAt(text.length() - 1);
            String gap = last == '\n' ? "" : last == OBJECT ? " " : "   ";
            styles.add(style(offset + text.length() + gap.length(), meta.length(), metaColor(), SWT.NORMAL));
            text.append(gap).append(meta);
        }
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
            StyleRange range = style(offset + text.length(), line.length(),
                    use.status() == ToolUse.Status.FAILED ? errorColor() : metaColor(), SWT.NORMAL);
            String details = use.details();
            if (details != null) {
                range.data = new Hint(details);
            }
            styles.add(range);
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

    /**
     * Файлы, которые модель приложила к ответу: по строке-карточке на файл —
     * значок, имя, размер, «Сохранить…», «Открыть». Строка с фоном, как у кода.
     */
    private void files(ChatMessage message, int offset, StringBuilder text, List<StyleRange> styles, List<int[]> codeRanges) {
        List<Attachment> files = message.attachments().stream().filter(file -> !Attachment.REVIEW.equals(file.mediaType())).toList();
        if (files.isEmpty()) {
            return;
        }
        text.append('\n');
        for (Attachment file : files) {
            text.append('\n');
            int start = offset + text.length();
            Runnable open = () -> callbacks.openAttachment(file);
            StyleRange place = new StyleRange();
            place.start = start;
            place.length = 1;
            place.metrics = new GlyphMetrics(ICON - 3, 3, ICON + 6);
            place.data = new FileIcon(Icons.image(Icons.FILE), open);
            styles.add(place);
            text.append(OBJECT);
            StyleRange name = new StyleRange(offset + text.length(), file.name().length(), linkColor(), null, SWT.BOLD);
            name.data = open;
            styles.add(name);
            text.append(file.name());
            String size = "  " + Attachment.size(file.bytes().length) + "   ";
            styles.add(style(offset + text.length(), size.length(), metaColor(), SWT.NORMAL));
            text.append(size);
            link(text, styles, offset, SAVE_FILE, () -> callbacks.saveAttachment(file));
            text.append("   ");
            link(text, styles, offset, OPEN_FILE, open);
            codeRanges.add(new int[] { start, offset + text.length() });
        }
    }

    /**
     * Карточка ревью: итог («Ревью · 2 ошибки · 1 замечание»), затем по
     * строке на находку — цветная точка серьёзности, «Модуль:строка» ссылкой
     * на строку и суть; как исправить — подсказкой при наведении.
     */
    private void review(ChatMessage message, int offset, StringBuilder text, List<StyleRange> styles, List<int[]> codeRanges) {
        for (Attachment file : message.attachments()) {
            if (!Attachment.REVIEW.equals(file.mediaType())) {
                continue;
            }
            List<Finding> findings;
            try {
                findings = Finding.fromJson(file.data());
            } catch (RuntimeException e) {
                continue;
            }
            text.append("\n\n");
            int start = offset + text.length();
            String head = "Ревью · " + Finding.summary(findings);
            styles.add(style(offset + text.length(), head.length(), null, SWT.BOLD));
            text.append(head);
            if (!findings.isEmpty()) {
                text.append("   ");
                link(text, styles, offset, CLEAR_MARKERS, callbacks::clearReviewMarkers);
            }
            for (Finding finding : findings) {
                text.append('\n');
                styles.add(style(offset + text.length(), BULLET.length(), severityColor(finding.severity()), SWT.NORMAL));
                text.append(BULLET).append("  ");
                link(text, styles, offset, finding.module() + ":" + finding.line(), () -> callbacks.openFinding(finding));
                String title = "  " + finding.title();
                StyleRange range = style(offset + text.length(), title.length(), null, SWT.NORMAL);
                if (!finding.fix().isEmpty()) {
                    range.data = new Hint("Как исправить: " + finding.fix());
                }
                styles.add(range);
                text.append(title);
            }
            codeRanges.add(new int[] { start, offset + text.length() });
        }
    }

    private Color severityColor(Finding.Severity severity) {
        return switch (severity) {
            case ERROR -> errorColor();
            case WARNING -> new Color(new RGB(0xD9, 0x77, 0x06));
            case INFO -> new Color(new RGB(0x25, 0x63, 0xEB));
        };
    }

    private void link(StringBuilder text, List<StyleRange> styles, int offset, String label, Runnable run) {
        StyleRange link = new StyleRange(offset + text.length(), label.length(), linkColor(), null);
        link.data = run;
        styles.add(link);
        text.append(label);
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

    /** Миниатюры картинок (щелчок — открыть целиком) и строка «Вложения:» с подписями. */
    private void attachments(ChatMessage message, int offset, StringBuilder text, List<StyleRange> styles) {
        if (message.attachments().isEmpty()) {
            return;
        }
        boolean shown = false;
        for (Attachment image : message.images()) {
            Thumbnail thumbnail = thumbnail(image);
            if (thumbnail != null) {
                StyleRange place = new StyleRange();
                place.start = offset + text.length();
                place.length = 1;
                place.metrics = new GlyphMetrics(thumbnail.height() + 2, 0, thumbnail.width() + THUMB_GAP);
                place.data = thumbnail;
                styles.add(place);
                text.append(OBJECT);
                shown = true;
            }
        }
        if (shown) {
            text.append('\n');
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

    /** Подсказка над текстом ленты: полный текст ошибки вызова. */
    record Hint(String text) {
    }

    private static StyleRange style(int start, int length, Color foreground, int fontStyle) {
        return new StyleRange(start, length, foreground, null, fontStyle);
    }

    /** Миниатюра картинки; не картинка (повреждённые данные) — null. */
    private Thumbnail thumbnail(Attachment image) {
        Thumbnail cached = thumbnails.get(image);
        if (cached != null && !cached.image().isDisposed()) {
            return cached;
        }
        try {
            ImageData data = new ImageLoader().load(new ByteArrayInputStream(Base64.getDecoder().decode(image.data())))[0];
            double scale = Math.min(1.0, Math.min((double) THUMB_WIDTH / data.width, (double) THUMB_HEIGHT / data.height));
            int width = Math.max(1, (int) Math.round(data.width * scale));
            int height = Math.max(1, (int) Math.round(data.height * scale));
            Image full = new Image(history.getDisplay(), data);
            Image small = new Image(history.getDisplay(), width, height);
            GC gc = new GC(small);
            try {
                gc.setBackground(history.getBackground());
                gc.fillRectangle(0, 0, width, height);
                gc.setAntialias(SWT.ON);
                gc.setInterpolation(SWT.HIGH);
                gc.drawImage(full, 0, 0, data.width, data.height, 0, 0, width, height);
            } finally {
                gc.dispose();
                full.dispose();
            }
            Thumbnail thumbnail = new Thumbnail(small, width, height, () -> callbacks.openAttachment(image));
            thumbnails.put(image, thumbnail);
            return thumbnail;
        } catch (SWTException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    /** Рисует миниатюру на её месте в ленте, в тонкой рамке. */
    void paintObject(PaintObjectEvent event) {
        if (event.style.data instanceof Thumbnail thumbnail && !thumbnail.image().isDisposed()) {
            // event.y — верх места под эту картинку (не строки): картинки разной высоты стоят по нижнему краю.
            event.gc.drawImage(thumbnail.image(), event.x + 1, event.y + 1);
            event.gc.setForeground(metaColor());
            event.gc.drawRectangle(event.x, event.y, thumbnail.width() + 1, thumbnail.height() + 1);
        } else if (event.style.data instanceof FileIcon icon && !icon.image().isDisposed()) {
            event.gc.drawImage(icon.image(), event.x + 2, event.y);
        } else if (event.style.data instanceof ActionIcon icon && !icon.image().isDisposed()) {
            event.gc.drawImage(icon.image(), event.x, event.y);
        }
    }

    /** Освобождает картинки миниатюр. */
    void dispose() {
        thumbnails.values().forEach(thumbnail -> thumbnail.image().dispose());
        thumbnails.clear();
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

    /** Цвет пузыря вопроса пользователя: цвет выделения, его кладут полупрозрачным поверх ленты. */
    Color bubbleColor() {
        return history.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION);
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
