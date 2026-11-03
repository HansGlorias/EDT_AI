package io.github.hansglorias.edtai.core.llm;

import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Id;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.RequestType;

/** Корреляция физических запросов без связи с пользовательским чатом или содержимым. */
record RequestContext(Id runId, RequestType type, int retryOrdinal) {
    static RequestContext retry(RequestContext context) {
        return context == null ? null : new RequestContext(context.runId(), RequestType.RETRY, context.retryOrdinal() + 1);
    }
}
