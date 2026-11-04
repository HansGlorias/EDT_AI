package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanFileTest {

    @TempDir
    Path temp;

    @Test
    void writesPlanWithHintAndReadsUserEdit() throws Exception {
        PlanFile file = PlanFile.of(temp.resolve("plans"), "chat/1:2");
        assertEquals("chat_1_2.md", file.path().getFileName().toString());
        assertFalse(file.exists());
        assertEquals("", file.read());

        String plan = "1. Прочитать модуль\n2. Исправить проверку";
        file.write(plan);
        String saved = Files.readString(file.path(), StandardCharsets.UTF_8);
        assertTrue(saved.startsWith(PlanFile.HEADER));
        assertTrue(file.matches(plan));
        assertFalse(file.matches("другой план"));
        assertEquals(plan, file.read());
        assertFalse(PlanFile.edited(plan, file.read()), "не тронутый план не считается исправленным");

        // Правка в редакторе Windows: \r\n и концевые пробелы — не правка; новый пункт — правка.
        Files.writeString(file.path(), saved.replace("\n", "\r\n").replace("модуль", "модуль   "), StandardCharsets.UTF_8);
        assertFalse(PlanFile.edited(plan, file.read()));
        Files.writeString(file.path(), saved.replace("проверку", "проверку\n3. Добавить тест"), StandardCharsets.UTF_8);
        assertTrue(file.matches(plan), "файл всё ещё для этого плана");
        assertTrue(PlanFile.edited(plan, file.read()));
        assertEquals("1. Прочитать модуль\n2. Исправить проверку\n3. Добавить тест", file.read());
        assertFalse(PlanFile.edited(plan, "  "), "пустой файл — не редакция");
    }

    @Test
    void executeMessageCarriesEditedPlanOnlyWhenEdited() {
        assertEquals(PlanApproval.EXECUTE, PlanApproval.execute("", null));
        assertEquals(PlanApproval.EXECUTE + "\nОтветы на уточнения:\nКуда — сюда", PlanApproval.execute("Куда — сюда", ""));
        String own = PlanApproval.execute("", "1. Свой план\n");
        assertTrue(own.startsWith(PlanApproval.EXECUTE_EDITED));
        assertTrue(own.endsWith("Утверждённый план:\n1. Свой план"));
    }
}
