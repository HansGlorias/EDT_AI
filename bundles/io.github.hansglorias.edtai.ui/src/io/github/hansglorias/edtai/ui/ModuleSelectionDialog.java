package io.github.hansglorias.edtai.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.FilteredItemsSelectionDialog;
import org.eclipse.ui.dialogs.SearchPattern;
import org.osgi.framework.FrameworkUtil;

import io.github.hansglorias.edtai.core.context.BslModulePath;
import io.github.hansglorias.edtai.core.context.ModuleSearch;

/**
 * «Прикрепить модуль…»: модули BSL открытых проектов рабочей области с
 * поиском по имени объекта по-русски («Номенклатура», «ОбщМод») и по пути.
 */
final class ModuleSelectionDialog extends FilteredItemsSelectionDialog {

    private static final String SETTINGS = ModuleSelectionDialog.class.getName();

    ModuleSelectionDialog(Shell shell) {
        super(shell, true);
        setTitle("Прикрепить модуль к чату AI");
        setMessage("Имя объекта или путь модуля (например, «Номенклатура», «ОбщМод», «CommonModules»):");
        setListLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                return element instanceof IFile file ? name(file) : super.getText(element);
            }

            @Override
            public Image getImage(Object element) {
                return PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_OBJ_FILE);
            }
        });
        setDetailsLabelProvider(new LabelProvider() {
            @Override
            public String getText(Object element) {
                return element instanceof IFile file ? file.getFullPath().makeRelative().toString() : super.getText(element);
            }
        });
    }

    /** Имя модуля в нотации 1С, а если путь не похож на модуль EDT — путь в проекте. */
    static String name(IFile file) {
        String path = file.getProjectRelativePath().toString();
        return BslModulePath.metadataName(path).orElse(path);
    }

    @Override
    protected Control createExtendedContentArea(Composite parent) {
        return null;
    }

    @Override
    protected IDialogSettings getDialogSettings() {
        IDialogSettings root = PlatformUI.getDialogSettingsProvider(FrameworkUtil.getBundle(ModuleSelectionDialog.class))
                .getDialogSettings();
        IDialogSettings section = root.getSection(SETTINGS);
        return section != null ? section : root.addNewSection(SETTINGS);
    }

    @Override
    protected IStatus validateItem(Object item) {
        return Status.OK_STATUS;
    }

    @Override
    protected ItemsFilter createFilter() {
        return new ItemsFilter(new SearchPattern(SearchPattern.RULE_PATTERN_MATCH)) {
            private final ModuleSearch search = new ModuleSearch(getPattern());

            @Override
            public boolean matchItem(Object item) {
                return item instanceof IFile file && search.matches(name(file), file.getFullPath().toString());
            }

            @Override
            public boolean isConsistentItem(Object item) {
                return item instanceof IFile file && file.exists();
            }

            @Override
            public boolean isSubFilter(ItemsFilter filter) {
                // Сокращения («ОбщМод») не сужаются монотонно — пересчитываем список целиком.
                return false;
            }

            @Override
            public boolean equalsFilter(ItemsFilter filter) {
                return filter != null && getPattern().equals(filter.getPattern());
            }
        };
    }

    @Override
    protected Comparator<?> getItemsComparator() {
        return Comparator.comparing((Object item) -> name((IFile) item), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(item -> ((IFile) item).getFullPath().toString());
    }

    @Override
    protected void fillContentProvider(AbstractContentProvider contentProvider, ItemsFilter itemsFilter, IProgressMonitor monitor)
            throws CoreException {
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        monitor.beginTask("Поиск модулей", projects.length);
        try {
            for (IProject project : projects) {
                if (monitor.isCanceled()) {
                    return;
                }
                visit(project, file -> contentProvider.add(file, itemsFilter));
                monitor.worked(1);
            }
        } finally {
            monitor.done();
        }
    }

    /** Модули всех открытых проектов: для подсказок после «@». */
    static List<IFile> modules() {
        List<IFile> modules = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            try {
                visit(project, modules::add);
            } catch (CoreException e) {
                // Проект закрылся во время обхода: без его модулей.
            }
        }
        return modules;
    }

    private static void visit(IProject project, Consumer<IFile> modules) throws CoreException {
        if (project.isAccessible()) {
            project.accept(proxy -> {
                if (proxy.getType() == IResource.FILE && proxy.getName().endsWith(".bsl")) {
                    modules.accept((IFile) proxy.requestResource());
                }
                // Служебные каталоги (.settings, bin, target) — не модули конфигурации.
                return proxy.getType() != IResource.FOLDER || !proxy.isDerived() && !proxy.getName().startsWith(".");
            }, IResource.NONE);
        }
    }

    @Override
    public String getElementName(Object item) {
        return item instanceof IFile file ? name(file) : String.valueOf(item);
    }

    // ---- для тестов ----

    /** Выделенные в списке модули. */
    List<?> selectedItems() {
        return getSelectedItems().toList();
    }
}
