package io.github.hansglorias.edtai.ui;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.hansglorias.edtai.core.tools.WorkspaceFiles;

/**
 * Проекты рабочей области Eclipse для инструментов модели. Инструменты
 * работают в фоновом потоке; несохранённый текст редактора берётся в
 * UI-потоке.
 */
final class EclipseWorkspaceFiles implements WorkspaceFiles {

    private final String preferred;
    private final String activeFile;
    private final int activeLine;

    private EclipseWorkspaceFiles(String preferred, String activeFile, int activeLine) {
        this.preferred = preferred;
        this.activeFile = activeFile;
        this.activeLine = activeLine;
    }

    /**
     * Предпочтительный проект — проект файла в активном редакторе; там же
     * файл и строка курсора. Можно вызывать из любого потока.
     */
    static EclipseWorkspaceFiles current() {
        AtomicReference<EclipseWorkspaceFiles> result = new AtomicReference<>(new EclipseWorkspaceFiles(null, null, 0));
        inUi(() -> {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            ITextEditor editor = page == null ? null : EditorAccess.textEditor(page.getActiveEditor());
            IFile file = editor == null ? null : EditorAccess.file(editor);
            if (file != null) {
                int line = editor.getSelectionProvider().getSelection() instanceof ITextSelection selection ? selection.getStartLine() + 1 : 0;
                result.set(new EclipseWorkspaceFiles(file.getProject().getName(), file.getProjectRelativePath().toString(), line));
            }
        });
        return result.get();
    }

    @Override
    public Optional<String> activeFile() {
        return Optional.ofNullable(activeFile);
    }

    @Override
    public int activeLine() {
        return activeLine;
    }

    @Override
    public List<String> projects() {
        return Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects()).filter(IProject::isOpen).map(IProject::getName)
                .sorted().toList();
    }

    @Override
    public Optional<Path> root(String project) {
        IProject resource = ResourcesPlugin.getWorkspace().getRoot().getProject(project);
        return resource.isOpen() && resource.getLocation() != null ? Optional.of(resource.getLocation().toFile().toPath())
                : Optional.empty();
    }

    @Override
    public Optional<String> preferredProject() {
        return Optional.ofNullable(preferred);
    }

    @Override
    public Optional<String> unsavedText(String project, String path) {
        IFile file = ResourcesPlugin.getWorkspace().getRoot().getProject(project).getFile(path);
        AtomicReference<String> text = new AtomicReference<>();
        Runnable read = () -> {
            ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
            if (buffer != null && buffer.isDirty()) {
                text.set(buffer.getDocument().get());
            }
        };
        inUi(read);
        return Optional.ofNullable(text.get());
    }

    /** Выполняет в UI-потоке и ждёт. */
    private static void inUi(Runnable runnable) {
        Display display = PlatformUI.getWorkbench().getDisplay();
        if (Display.getCurrent() != null) {
            runnable.run();
        } else if (!display.isDisposed()) {
            display.syncExec(runnable);
        }
    }
}
