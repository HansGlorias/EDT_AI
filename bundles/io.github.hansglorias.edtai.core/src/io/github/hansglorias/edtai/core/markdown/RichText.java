package io.github.hansglorias.edtai.core.markdown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.TreeSet;

/**
 * Отрисованный Markdown: текст для показа и оформление его отрезков.
 */
public record RichText(String text, List<Span> spans, List<CodeBlock> codeBlocks) {

    public RichText {
        spans = List.copyOf(spans);
        codeBlocks = List.copyOf(codeBlocks);
    }

    /**
     * Отрезки без пересечений, в порядке текста: на каждом — все виды
     * оформления, которые его покрывают. Отрезки без оформления пропущены.
     */
    public List<Segment> segments() {
        TreeSet<Integer> bounds = new TreeSet<>();
        for (Span span : spans) {
            if (span.length() > 0) {
                bounds.add(span.start());
                bounds.add(span.end());
            }
        }
        List<Segment> result = new ArrayList<>();
        if (bounds.isEmpty()) {
            return result;
        }
        int from = bounds.first();
        for (int to : bounds.tailSet(from, false)) {
            EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
            int level = 0;
            String link = null;
            String action = null;
            for (Span span : spans) {
                if (span.start() <= from && span.end() >= to && span.length() > 0) {
                    kinds.add(span.kind());
                    switch (span.kind()) {
                        case HEADING -> level = Integer.parseInt(span.data());
                        case LINK -> link = span.data();
                        case ACTION -> action = span.data();
                        default -> {
                            // данных нет
                        }
                    }
                }
            }
            if (!kinds.isEmpty()) {
                result.add(new Segment(from, to - from, Collections.unmodifiableSet(kinds), level, link, action));
            }
            from = to;
        }
        return result;
    }
}
