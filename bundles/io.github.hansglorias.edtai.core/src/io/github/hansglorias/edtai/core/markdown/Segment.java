package io.github.hansglorias.edtai.core.markdown;

import java.util.Set;

/**
 * Непересекающийся отрезок текста с итоговым оформлением: все виды, которые
 * на него приходятся.
 *
 * @param headingLevel уровень заголовка, 0 — не заголовок
 * @param link         адрес ссылки или {@code null}
 * @param action       действие или {@code null}
 */
public record Segment(int start, int length, Set<Kind> kinds, int headingLevel, String link, String action) {

    public boolean has(Kind kind) {
        return kinds.contains(kind);
    }

    /** Моноширинный ли отрезок. */
    public boolean monospace() {
        return has(Kind.CODE) || has(Kind.CODE_BLOCK) || has(Kind.CODE_HEADER) || has(Kind.TABLE);
    }
}
