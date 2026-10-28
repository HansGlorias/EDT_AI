package io.github.hansglorias.edtai.ui;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.Position;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.hansglorias.edtai.core.context.BslMethods;
import io.github.hansglorias.edtai.core.context.ContextItem;

/**
 * Фрагмент над полем ввода: файл и строки (или модуль целиком). Текст
 * берётся в момент отправки — из открытого редактора, если файл открыт
 * (с несохранёнными правками), иначе из файла. Пока документ открыт,
 * строки отслеживаются через {@link Position} и не сбиваются от правок выше.
 */
final class AttachedContext {

    private final IFile file;
    /** Строки с 1; у модуля целиком — 0. Обновляются по {@link #position}. */
    private int firstLine;
    private int lastLine;
    private final boolean whole;
    /** Документ, в котором отслеживаются строки, и позиция в нём; {@code null} — не отслеживаем. */
    private IDocument document;
    private Position position;
    /** Что отправить, если файла уже нет: например, фрагмент вернули из ленты через «Изменить». */
    private final ContextItem fallback;

    private AttachedContext(IFile file, int firstLine, int lastLine, boolean whole, ContextItem fallback) {
        this.file = file;
        this.firstLine = firstLine;
        this.lastLine = lastLine;
        this.whole = whole;
        this.fallback = fallback;
    }

    /**
     * Из редактора: выделенные строки целиком; без выделения — метод под
     * курсором, вне метода — модуль целиком.
     *
     * @return {@code null}, если в редакторе не файл рабочей области
     */
    static AttachedContext fromEditor(ITextEditor editor) {
        IFile file = EditorAccess.file(editor);
        IDocument document = EditorAccess.document(editor);
        if (file == null || document == null || !(editor.getSelectionProvider().getSelection() instanceof ITextSelection selection)) {
            return null;
        }
        try {
            int first = document.getLineOfOffset(selection.getOffset());
            int last;
            if (selection.getLength() > 0) {
                int end = selection.getOffset() + selection.getLength();
                last = document.getLineOfOffset(end);
                // Выделение до начала следующей строки — это строки выше неё.
                if (last > first && document.getLineOffset(last) == end) {
                    last--;
                }
            } else {
                Optional<BslMethods.Method> method = BslMethods.enclosing(document.get(), first);
                if (method.isEmpty()) {
                    return new AttachedContext(file, 0, 0, true, null);
                }
                first = method.get().firstLine();
                last = method.get().lastLine();
            }
            AttachedContext context = new AttachedContext(file, first + 1, last + 1, false, null);
            context.track(document);
            return context;
        } catch (BadLocationException e) {
            return null;
        }
    }

    /** Модуль целиком. */
    static AttachedContext wholeFile(IFile file) {
        return new AttachedContext(file, 0, 0, true, null);
    }

    /** Фрагмент из отправленного сообщения: те же строки того же файла, если он ещё есть. */
    static AttachedContext fromItem(ContextItem item) {
        IFile file = ResourcesPlugin.getWorkspace().getRoot().getProject(item.project()).getFile(item.path());
        return new AttachedContext(file, item.whole() ? 0 : item.firstLine(), item.whole() ? 0 : item.requestedLastLine(),
                item.whole(), item);
    }

    IFile file() {
        return file;
    }

    boolean isWhole() {
        return whole;
    }

    /** Текущие строки с 1, у модуля целиком — {@code 0, 0}. */
    int[] lines() {
        updateLines();
        return new int[] { firstLine, lastLine };
    }

    boolean sameAs(AttachedContext other) {
        return file.equals(other.file) && whole == other.whole && Arrays.equals(lines(), other.lines());
    }

    /**
     * Текст фрагмента сейчас.
     *
     * @return {@code null}, если файла нет и отправлять нечего
     */
    ContextItem snapshot() {
        String text = currentText();
        if (text == null) {
            return fallback;
        }
        List<String> all = List.of(text.split("\r\n|\r|\n", -1));
        int total = all.size() > 1 && all.get(all.size() - 1).isEmpty() ? all.size() - 1 : all.size();
        String path = file.getProjectRelativePath().toString();
        String project = file.getProject().getName();
        if (whole) {
            return ContextItem.of(project, path, 1, total, total, String.join("\n", all.subList(0, total)));
        }
        updateLines();
        int first = Math.max(1, Math.min(firstLine, total));
        int last = Math.max(first, Math.min(lastLine, total));
        return ContextItem.of(project, path, first, last, total, String.join("\n", all.subList(first - 1, last)));
    }

    /** Перестаёт отслеживать строки: фрагмент убрали или отправили. */
    void dispose() {
        if (document != null && position != null) {
            document.removePosition(position);
        }
        document = null;
        position = null;
    }

    private void track(IDocument tracked) throws BadLocationException {
        int start = tracked.getLineOffset(firstLine - 1);
        int end = tracked.getLineOffset(lastLine - 1) + lineLength(tracked, lastLine - 1);
        position = new Position(start, end - start);
        tracked.addPosition(position);
        document = tracked;
    }

    private void updateLines() {
        if (document == null || position == null || whole) {
            return;
        }
        if (position.isDeleted() || openDocument() != document) {
            // Строки удалили или документ закрыли: остаются последние известные номера.
            dispose();
            return;
        }
        try {
            firstLine = document.getLineOfOffset(position.getOffset()) + 1;
            lastLine = document.getLineOfOffset(position.getOffset() + position.getLength()) + 1;
        } catch (BadLocationException e) {
            dispose();
        }
    }

    private static int lineLength(IDocument document, int line) throws BadLocationException {
        String delimiter = document.getLineDelimiter(line);
        return document.getLineLength(line) - (delimiter == null ? 0 : delimiter.length());
    }

    /** Документ открытого в редакторе файла или {@code null}. */
    private IDocument openDocument() {
        ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
        return buffer == null ? null : buffer.getDocument();
    }

    private String currentText() {
        IDocument open = openDocument();
        if (open != null) {
            return open.get();
        }
        if (!file.exists()) {
            return null;
        }
        try (InputStream in = file.getContents(true)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            String text = out.toString(Charset.forName(file.getCharset()));
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (Exception e) {
            return null;
        }
    }
}
