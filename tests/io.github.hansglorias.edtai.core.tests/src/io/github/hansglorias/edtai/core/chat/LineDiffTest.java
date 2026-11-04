package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class LineDiffTest {

    @Test
    void twoDistantEditsAreTwoSmallHunks() {
        String before = IntStream.rangeClosed(1, 40).mapToObj(i -> "Строка" + i).collect(Collectors.joining("\r\n", "﻿", "\r\n"));
        String after = before.replace("Строка5\r\n", "Строка5\r\nНовая\r\n").replace("Строка30\r\n", "Строка30 изменена\r\n");
        assertEquals("""
                @@ -4,4 +4,5 @@
                 Строка4
                 Строка5
                +Новая
                 Строка6
                 Строка7
                @@ -28,5 +29,5 @@
                 Строка28
                 Строка29
                -Строка30
                +Строка30 изменена
                 Строка31
                 Строка32
                """, LineDiff.unified(before, after));
        assertEquals("", LineDiff.unified(before, before));
    }

    @Test
    void exportCarriesDiffComputedFromFullTexts() throws Exception {
        String before = IntStream.rangeClosed(1, 3000).mapToObj(i -> "Строка" + i).collect(Collectors.joining("\n"));
        String after = before.replace("Строка2999", "Строка2999 изменена");
        assertTrue(LineDiff.unified(before, after).contains("+Строка2999 изменена\n"),
                "правка в конце длинного модуля видна, хотя полные тексты в выгрузке обрезаются");
    }
}
