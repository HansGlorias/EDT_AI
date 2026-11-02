package io.github.hansglorias.edtai.core.settings;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import io.github.hansglorias.edtai.core.chat.AgentMode;

/** Последний выбранный режим рабочей области; чтение не меняет настройки. */
public final class AgentModeStore {

    public static final String KEY = "agentMode";
    public static final String LEGACY_AUTO = "autoApproveTools";
    public static final String LEGACY_READ_ONLY = "readOnlyMode";

    private final IEclipsePreferences preferences;
    private CompletableFuture<Void> saving = CompletableFuture.completedFuture(null);

    public AgentModeStore(IEclipsePreferences preferences) {
        this.preferences = preferences;
    }

    private static final class Workspace {
        static final AgentModeStore STORE = new AgentModeStore(InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER));
    }

    /** Один писатель на рабочую область, общий для всех экземпляров вида. */
    public static AgentModeStore workspace() {
        return Workspace.STORE;
    }

    public AgentMode load() {
        return AgentMode.parse(preferences.get(KEY, ""), preferences.getBoolean(LEGACY_AUTO, false),
                preferences.getBoolean(LEGACY_READ_ONLY, false));
    }

    /** Сразу включает режим в сеансе; запись на диск — последовательно, вне UI. */
    public synchronized CompletableFuture<Void> select(AgentMode mode) {
        preferences.put(KEY, mode.name());
        saving = saving.handle((unused, error) -> null).thenRunAsync(() -> {
            try {
                preferences.flush();
            } catch (BackingStoreException e) {
                throw new CompletionException(e);
            }
        }, command -> {
            Job job = Job.create("EDT AI: сохранение режима модели", monitor -> { command.run(); });
            job.setSystem(true);
            job.schedule();
        });
        return saving;
    }

    public synchronized CompletableFuture<Void> pendingSave() {
        return saving;
    }
}
