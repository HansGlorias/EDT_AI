package io.github.hansglorias.edtai.core.llm;

/** Защитная остановка потока: вместо испорченного хвоста сохраняется только полезный префикс. */
public final class GenerationStoppedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String safeText;

    GenerationStoppedException(String safeText) {
        super("Генерация остановлена: модель зациклилась на повторяющемся тексте. "
                + "Текст до повтора и выполненные операции сохранены; повторяющийся хвост удалён. "
                + "Автоматический повтор не выполнялся.");
        this.safeText = safeText;
    }

    /** Очищенный ответ; не включает причину остановки и не доказывает завершение задачи. */
    public String safeText() {
        return safeText;
    }
}
