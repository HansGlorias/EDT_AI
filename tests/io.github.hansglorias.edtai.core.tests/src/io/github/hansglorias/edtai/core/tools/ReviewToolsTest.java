package io.github.hansglorias.edtai.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.review.Finding;

class ReviewToolsTest {

    @TempDir
    Path temp;

    @Test
    void findingsGoToMarkersAndCard() throws Exception {
        Path module = temp.resolve("Демо/src/CommonModules/Цены/Module.bsl");
        Files.createDirectories(module.getParent());
        Files.writeString(module, "Процедура А()\nКонецПроцедуры\n");
        List<Finding> marked = new ArrayList<>();
        Tool tool = ReviewTools.create(new WorkspaceFiles() {
            @Override
            public List<String> projects() {
                return List.of("Демо");
            }

            @Override
            public Optional<Path> root(String project) {
                return Optional.of(temp.resolve(project));
            }
        }, marked::addAll).get(0);

        Tool.Result result = tool.call(Map.of("findings", List.of(
                Map.of("module", "ОбщийМодуль.Цены", "line", 2, "severity", "warning", "title", "Нет комментария", "fix", "Описать"),
                Map.of("module", "ОбщийМодуль.Цены", "line", 1, "severity", "error", "title", "Запрос в цикле"),
                Map.of("module", "ОбщийМодуль.Нет", "line", 1, "severity", "error", "title", "Выдумано"))));

        assertEquals("report_review", tool.name());
        assertFalse(tool.modifies(), "доступен и в режимах «План» и «Вопросы»");
        assertEquals(List.of("Запрос в цикле", "Нет комментария"), marked.stream().map(Finding::title).toList(), "сначала ошибки");
        assertEquals("src/CommonModules/Цены/Module.bsl", marked.get(0).path());
        assertEquals("ОбщийМодуль.Цены.Модуль", marked.get(0).module());
        assertEquals("1 ошибка · 1 замечание", result.outcome());
        assertTrue(result.text().contains("ОбщийМодуль.Нет: модуля нет в проекте"), result.text());
        assertEquals(Attachment.REVIEW, result.file().mediaType());
        assertEquals(marked, Finding.fromJson(result.file().data()));
    }
}
