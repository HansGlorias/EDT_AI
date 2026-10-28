package io.github.hansglorias.edtai.ui;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

/** Текстовый редактор, его файл и документ — через обычный Eclipse API, без API EDT. */
final class EditorAccess {

    private EditorAccess() {
    }

    /** Текстовый редактор части или {@code null}; у многостраничного редактора объекта EDT — текущая страница. */
    static ITextEditor textEditor(IEditorPart editor) {
        if (editor == null) {
            return null;
        }
        // Редактор объекта в EDT многостраничный: модуль — на одной из его страниц.
        if (editor instanceof MultiPageEditorPart multiPage && multiPage.getSelectedPage() instanceof IEditorPart page) {
            ITextEditor text = Adapters.adapt(page, ITextEditor.class);
            if (text != null) {
                return text;
            }
        }
        return Adapters.adapt(editor, ITextEditor.class);
    }

    /** Файл рабочей области, открытый в редакторе, или {@code null} (файл вне рабочей области, сравнение и т. п.). */
    static IFile file(ITextEditor editor) {
        return editor.getEditorInput() == null ? null : Adapters.adapt(editor.getEditorInput(), IFile.class);
    }

    static IDocument document(ITextEditor editor) {
        return editor.getDocumentProvider() == null ? null : editor.getDocumentProvider().getDocument(editor.getEditorInput());
    }

    /**
     * Открывает файл в редакторе и выделяет строки {@code firstLine..lastLine}
     * (с 1); {@code firstLine <= 0} — только открыть.
     *
     * @return редактор или {@code null}, если открыть не удалось
     */
    static ITextEditor reveal(IWorkbenchPage page, IFile file, int firstLine, int lastLine) throws PartInitException {
        IEditorPart part = IDE.openEditor(page, file, true);
        ITextEditor editor = textEditor(part);
        if (editor == null || firstLine <= 0) {
            return editor;
        }
        IDocument document = document(editor);
        if (document == null) {
            return editor;
        }
        try {
            int first = Math.min(firstLine, document.getNumberOfLines()) - 1;
            int last = Math.max(first, Math.min(lastLine, document.getNumberOfLines()) - 1);
            int start = document.getLineOffset(first);
            int end = document.getLineOffset(last) + document.getLineLength(last)
                    - (document.getLineDelimiter(last) == null ? 0 : document.getLineDelimiter(last).length());
            editor.selectAndReveal(start, end - start);
        } catch (BadLocationException e) {
            // Файл изменился так, что строк больше нет: просто открыли.
        }
        return editor;
    }
}
