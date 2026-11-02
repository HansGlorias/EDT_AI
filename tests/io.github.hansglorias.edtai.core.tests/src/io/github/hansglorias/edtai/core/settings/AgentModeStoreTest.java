package io.github.hansglorias.edtai.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;

import io.github.hansglorias.edtai.core.chat.AgentMode;

class AgentModeStoreTest {

    private final IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode("edtai.mode.tests." + UUID.randomUUID());

    @AfterEach
    void cleanup() throws Exception {
        preferences.removeNode();
    }

    @Test
    void everyModeSurvivesReloadFromBackingStore() throws Exception {
        AgentModeStore store = new AgentModeStore(preferences);
        for (AgentMode mode : AgentMode.values()) {
            store.select(mode).get(10, TimeUnit.SECONDS);
            reloadWithWrongCachedMode(mode);
            assertEquals(mode, new AgentModeStore(preferences).load());
            assertEquals(List.of(AgentModeStore.KEY), List.of(preferences.keys()), "сохраняется только режим");
        }
    }

    @Test
    void readsLegacyWithoutWritingAndExplicitSelectionWins() throws Exception {
        AgentModeStore store = new AgentModeStore(preferences);
        assertEquals(AgentMode.AGENT, store.load());
        preferences.putBoolean(AgentModeStore.LEGACY_AUTO, true);
        preferences.flush();
        assertEquals(AgentMode.AUTO, store.load());
        assertNull(preferences.get(AgentModeStore.KEY, null), "чтение не записывает миграцию");
        preferences.putBoolean(AgentModeStore.LEGACY_READ_ONLY, true);
        assertEquals(AgentMode.ASK, new AgentModeStore(preferences).load());

        store.select(AgentMode.AGENT).get(10, TimeUnit.SECONDS);
        reloadWithWrongCachedMode(AgentMode.AGENT);
        assertEquals(AgentMode.AGENT, new AgentModeStore(preferences).load(), "явный выбор важнее legacy-флажков");
    }

    @Test
    void slowFlushRunsOutsideCallerAndLastSelectionSurvives() throws Exception {
        Thread caller = Thread.currentThread();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        AgentModeStore store = new AgentModeStore(interceptFlush(() -> {
            assertFalse(Thread.currentThread() == caller, "flush не выполняется в вызывающем потоке");
            if (first.getAndSet(false)) {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
        }));
        try {
            var auto = store.select(AgentMode.AUTO);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertFalse(auto.isDone());
            store.select(AgentMode.PLAN);
            var last = store.select(AgentMode.ASK);
            assertEquals(AgentMode.ASK, store.load(), "последний выбор действует сразу, пока запись задержана");
            release.countDown();
            last.get(10, TimeUnit.SECONDS);
            reloadWithWrongCachedMode(AgentMode.ASK);
            assertEquals(AgentMode.ASK, new AgentModeStore(preferences).load());
        } finally {
            release.countDown();
            store.pendingSave().handle((unused, error) -> null).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void reportsFlushFailureAndCanSaveNextSelection() throws Exception {
        preferences.put(AgentModeStore.KEY, AgentMode.AGENT.name());
        preferences.flush();
        AtomicBoolean fail = new AtomicBoolean(true);
        AgentModeStore store = new AgentModeStore(interceptFlush(() -> {
            if (fail.getAndSet(false)) {
                throw new BackingStoreException("test write failure");
            }
        }));
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> store.select(AgentMode.AUTO).get(10, TimeUnit.SECONDS));
        assertInstanceOf(BackingStoreException.class, error.getCause());
        assertEquals(AgentMode.AUTO, store.load(), "в текущем сеансе выбор действует");
        preferences.sync();
        assertEquals(AgentMode.AGENT, store.load(), "ошибка не выдаётся за сохранение на диск");

        store.select(AgentMode.AUTO).get(10, TimeUnit.SECONDS);
        reloadWithWrongCachedMode(AgentMode.AUTO);
        assertEquals(AgentMode.AUTO, new AgentModeStore(preferences).load());
    }

    /** Убирает правильное значение из кэша: sync заново читает сохранённый backing store Eclipse. */
    private void reloadWithWrongCachedMode(AgentMode expected) throws Exception {
        AgentMode wrong = expected == AgentMode.AUTO ? AgentMode.AGENT : AgentMode.AUTO;
        preferences.put(AgentModeStore.KEY, wrong.name());
        assertEquals(wrong, new AgentModeStore(preferences).load());
        preferences.sync();
    }

    private interface BeforeFlush {
        void run() throws Exception;
    }

    private IEclipsePreferences interceptFlush(BeforeFlush before) {
        return (IEclipsePreferences) Proxy.newProxyInstance(IEclipsePreferences.class.getClassLoader(),
                new Class<?>[] { IEclipsePreferences.class }, (proxy, method, args) -> {
                    if (method.getName().equals("flush")) {
                        before.run();
                    }
                    try {
                        return method.invoke(preferences, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
