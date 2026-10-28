package io.github.hansglorias.edtai.ui;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.graphics.Image;

/**
 * Окно сравнения, только для чтения: модуль сейчас (слева) и до правок
 * модели в этом ответе (справа). Оба — текст: так сравнение одинаково
 * работает с любой версией EDT, без её просмотрщика BSL.
 */
final class ModelChangesCompare extends CompareEditorInput {

    private final IFile file;
    private final String now;
    private final String before;

    ModelChangesCompare(IFile file, String now, String before) {
        super(configuration(file));
        this.file = file;
        this.now = now;
        this.before = before;
        setTitle("Правки модели: " + file.getName());
    }

    private static CompareConfiguration configuration(IFile file) {
        CompareConfiguration configuration = new CompareConfiguration();
        configuration.setLeftLabel(file.getFullPath().makeRelative() + " — сейчас");
        configuration.setLeftEditable(false);
        configuration.setRightLabel("До правок модели");
        configuration.setRightEditable(false);
        return configuration;
    }

    @Override
    protected Object prepareInput(IProgressMonitor monitor) {
        return new DiffNode(new Text(file.getName(), now), new Text(file.getName(), before));
    }

    /** Текст «до» для правой стороны. */
    private record Text(String name, String content) implements ITypedElement, IEncodedStreamContentAccessor {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Image getImage() {
            return null;
        }

        @Override
        public String getType() {
            return ITypedElement.TEXT_TYPE;
        }

        @Override
        public InputStream getContents() {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String getCharset() {
            return StandardCharsets.UTF_8.name();
        }
    }
}
