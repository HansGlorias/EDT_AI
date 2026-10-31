package io.github.hansglorias.edtai.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import io.github.hansglorias.edtai.core.review.Finding;

/**
 * Находки ревью — маркерами EDT AI: значок у строки в редакторе и строка в
 * окне «Проблемы». Новое ревью файла заменяет его прежние маркеры; «Убрать
 * замечания ревью» — снимает все.
 */
final class ReviewMarkers {

    /** Тип маркера из plugin.xml: проблема и текстовый маркер — их показывают и редактор, и «Проблемы». */
    static final String TYPE = "io.github.hansglorias.edtai.ui.review";
    static final String FIX = "fix";

    private ReviewMarkers() {
    }

    /** Ставит маркеры находок; у затронутых файлов прежние маркеры ревью снимаются. */
    static void show(List<Finding> findings) {
        if (findings.isEmpty()) {
            return;
        }
        run("EDT AI: замечания ревью", root -> {
            Map<IFile, List<Finding>> byFile = new LinkedHashMap<>();
            for (Finding finding : findings) {
                byFile.computeIfAbsent(file(root, finding), key -> new java.util.ArrayList<>()).add(finding);
            }
            for (Map.Entry<IFile, List<Finding>> entry : byFile.entrySet()) {
                IFile file = entry.getKey();
                if (!file.exists()) {
                    continue;
                }
                file.deleteMarkers(TYPE, false, IResource.DEPTH_ZERO);
                for (Finding finding : entry.getValue()) {
                    IMarker marker = file.createMarker(TYPE);
                    marker.setAttribute(IMarker.SEVERITY, switch (finding.severity()) {
                        case ERROR -> IMarker.SEVERITY_ERROR;
                        case WARNING -> IMarker.SEVERITY_WARNING;
                        case INFO -> IMarker.SEVERITY_INFO;
                    });
                    marker.setAttribute(IMarker.LINE_NUMBER, finding.line());
                    marker.setAttribute(IMarker.MESSAGE, message(finding));
                    marker.setAttribute(FIX, finding.fix());
                }
            }
        });
    }

    /** Снимает все маркеры ревью в рабочей области. */
    static void clear() {
        run("EDT AI: убрать замечания ревью", root -> root.deleteMarkers(TYPE, false, IResource.DEPTH_INFINITE));
    }

    /** Текст в «Проблемах» и подсказке редактора: «EDT AI: суть. Исправить: как». */
    static String message(Finding finding) {
        return "EDT AI: " + finding.title() + (finding.fix().isEmpty() ? "" : ". Исправить: " + finding.fix());
    }

    static IFile file(IWorkspaceRoot root, Finding finding) {
        return root.getProject(finding.project()).getFile(finding.path());
    }

    private interface Work {
        void run(IWorkspaceRoot root) throws CoreException;
    }

    private static void run(String name, Work work) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        WorkspaceJob job = new WorkspaceJob(name) {
            @Override
            public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
                work.run(root);
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setRule(root);
        job.schedule();
    }
}
