package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CommitMessageTest {

    @Test
    void firstCodeBlockOrWholeAnswer() {
        assertEquals("добавил очередь сообщений\n\nпока модель отвечает, enter ставит в очередь",
                CommitMessage.extract("Вот текст:\r\n\r\n```\r\nдобавил очередь сообщений\r\n\r\nпока модель отвечает, enter ставит в очередь\r\n```\r\n"
                        + "\r\n```\r\nвторой\r\n```"));
        assertEquals("поправил проведение", CommitMessage.extract("```text\nпоправил проведение\n```"));
        assertEquals("разрешил удалять строки в тч", CommitMessage.extract("  разрешил удалять строки в тч \n"));
        assertEquals("", CommitMessage.extract(null));
    }
}
