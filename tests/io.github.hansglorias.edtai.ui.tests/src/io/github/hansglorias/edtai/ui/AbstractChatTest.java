package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.osgi.framework.FrameworkUtil;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

/**
 * Общее для UI-тестов: чистые настройки и диалог перед каждым тестом,
 * фейковый сервер модели, ожидание в цикле событий и проверка, что в лог
 * Eclipse не попало ни одной ошибки.
 */
abstract class AbstractChatTest {

    static final long TIMEOUT_MS = 15_000;

    IWorkbenchPage page;
    FakeModelServer server;

    /** Ошибки, попавшие в лог Eclipse во время теста: например, исключения в цикле событий UI. */
    private final List<IStatus> loggedErrors = new CopyOnWriteArrayList<>();
    private final ILogListener logListener = (status, plugin) -> {
        if (status.matches(IStatus.ERROR)) {
            loggedErrors.add(status);
        }
    };

    @BeforeEach
    void prepareWorkbench() throws Exception {
        page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        page.getWorkbenchWindow().getShell().setBounds(0, 0, 720, 780);
        SettingsStore.workspace().save(AiSettings.defaults());
        InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER).remove(ChatView.AUTO_APPROVE);
        InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER).remove(ChatView.READ_ONLY);
        deleteChats();
        Platform.addLogListener(logListener);
    }

    @AfterEach
    void cleanWorkbench() throws Exception {
        IViewPart view = page.findView(ChatView.ID);
        if (view != null) {
            page.hideView(view);
        }
        page.closeAllEditors(false);
        if (server != null) {
            server.close();
        }
        SettingsStore.workspace().save(AiSettings.defaults());
        InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER).remove(ChatView.AUTO_APPROVE);
        InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER).remove(ChatView.READ_ONLY);
        deleteChats();
        Platform.removeLogListener(logListener);
        assertEquals(List.of(), loggedErrors.stream().map(AbstractChatTest::describe).toList());
    }

    static Path stateLocation() {
        return Platform.getStateLocation(FrameworkUtil.getBundle(ChatView.class)).toFile().toPath();
    }

    /** Все сохранённые диалоги одной строкой. */
    static String savedChats() throws IOException {
        Path directory = stateLocation().resolve(ChatView.CHATS_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                text.append(Files.readString(file)).append('\n');
            }
        }
        return text.toString();
    }

    static void deleteChats() throws IOException {
        Files.deleteIfExists(stateLocation().resolve(ChatView.HISTORY_FILE));
        Path directory = stateLocation().resolve(ChatView.CHATS_DIRECTORY);
        if (Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    ChatView showView() throws Exception {
        ChatView view = assertInstanceOf(ChatView.class, page.showView(ChatView.ID));
        page.setPartState(page.getReference(view), IWorkbenchPage.STATE_MAXIMIZED);
        return view;
    }

    /** Сервер отвечает одним и тем же потоком частей ответа. */
    FakeModelServer answering(List<String> parts) throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            List<String> events = new ArrayList<>();
            for (String part : parts) {
                events.add(openAiPart(part));
            }
            events.add("data: [DONE]\n\n");
            response.sse(events, 5);
        });
        configure(server);
        return server;
    }

    static void configure(FakeModelServer server) throws Exception {
        SettingsStore.workspace().save(new AiSettings(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", 0, "", 0, true, false, "",
                new ArrayList<>()));
    }

    /** Отправляет сообщение и ждёт окончания ответа. */
    static void ask(ChatView view, String question) {
        view.input().setText(question);
        view.send();
        waitUntil(() -> !view.session().isBusy() && view.session().messages().size() >= 2);
        view.refresh();
    }

    static String openAiPart(String text) {
        return "data: {\"choices\":[{\"delta\":{\"content\":" + Json.write(text) + "}}]}\n\n";
    }

    static void waitUntil(BooleanSupplier condition) {
        waitUntil(condition, () -> "");
    }

    /** @param state что показать в сообщении, если не дождались */
    static void waitUntil(BooleanSupplier condition, Supplier<String> state) {
        Display display = Display.getCurrent();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Не дождались условия за " + TIMEOUT_MS + " мс. Состояние: " + state.get());
            }
            if (!display.readAndDispatch()) {
                display.timerExec(20, () -> { });
                display.sleep();
            }
        }
    }

    /** Даёт отработать отложенным перерисовкам. */
    static void settle() {
        Display display = Display.getCurrent();
        long deadline = System.currentTimeMillis() + 200;
        while (System.currentTimeMillis() < deadline) {
            if (!display.readAndDispatch()) {
                display.timerExec(20, () -> { });
                display.sleep();
            }
        }
    }

    private static String describe(IStatus status) {
        StringWriter out = new StringWriter();
        out.append(status.getMessage());
        if (status.getException() != null) {
            status.getException().printStackTrace(new PrintWriter(out));
        }
        return out.toString();
    }
}
