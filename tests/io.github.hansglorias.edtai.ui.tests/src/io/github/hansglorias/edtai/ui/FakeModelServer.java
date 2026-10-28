package io.github.hansglorias.edtai.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HTTP-сервер для тестов вместо сервера модели: принимает запросы на
 * localhost и отвечает так, как скажет обработчик — частями, с паузами,
 * ошибками. Без зависимостей, на {@link ServerSocket}.
 *
 * <p>Упрощённая копия {@code FakeModelServer} из тестов core (фрагменты не
 * видят классы друг друга), только HTTP.
 */
final class FakeModelServer implements AutoCloseable {

    private static final int TLS_HANDSHAKE = 0x16;

    record Request(String method, String path, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    @FunctionalInterface
    interface Handler {
        void handle(Request request, Response response) throws Exception;
    }

    /** Ответ: заголовки, потом тело частями (chunked), каждая часть сразу отправляется. */
    static final class Response {

        private final OutputStream out;

        Response(OutputStream out) {
            this.out = out;
        }

        void start(int status, String contentType) throws IOException {
            start(status, contentType, Map.of());
        }

        /** Начало ответа с дополнительными заголовками: например, {@code Mcp-Session-Id}. */
        void start(int status, String contentType, Map<String, String> headers) throws IOException {
            StringBuilder head = new StringBuilder("HTTP/1.1 " + status + " X\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n");
            headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        void write(String part) throws IOException {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0) {
                return;
            }
            out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        void end() throws IOException {
            out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        /** Весь ответ разом. */
        void send(int status, String contentType, String body) throws IOException {
            start(status, contentType);
            write(body);
            end();
        }

        /** Поток SSE: каждая строка списка — отдельная порция, между порциями пауза. */
        void sse(List<String> parts, long pauseMillis) throws Exception {
            start(200, "text/event-stream");
            for (String part : parts) {
                write(part);
                if (pauseMillis > 0) {
                    Thread.sleep(pauseMillis);
                }
            }
            end();
        }
    }

    private final ServerSocket socket;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Handler handler = (request, response) -> response.send(500, "text/plain", "нет обработчика");

    private FakeModelServer(ServerSocket socket) {
        this.socket = socket;
        Thread thread = new Thread(this::accept, "fake model server");
        thread.setDaemon(true);
        thread.start();
    }

    static FakeModelServer http() throws IOException {
        return new FakeModelServer(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
    }

    FakeModelServer handler(Handler value) {
        this.handler = value;
        return this;
    }

    /** Адрес сервера: {@code http://localhost:порт}. */
    String url() {
        return "http://localhost:" + socket.getLocalPort();
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                Thread thread = new Thread(() -> serve(client), "fake model server: соединение");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            PushbackInputStream in = new PushbackInputStream(client.getInputStream());
            int first = in.read();
            if (first < 0) {
                return;
            }
            if (first == TLS_HANDSHAKE) {
                // Клиент пришёл по https на http-порт: отвечаем открытым текстом, как nginx.
                client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            in.unread(first);
            Request request = read(in);
            if (request == null) {
                return;
            }
            requests.add(request);
            Response response = new Response(client.getOutputStream());
            handler.handle(request, response);
        } catch (Exception e) {
            // Клиент закрыл соединение (отмена, таймаут) или сбой TLS — это и проверяют тесты.
        }
    }

    private static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            head.write(b);
            if (b == '\r' && (state == 0 || state == 2) || b == '\n' && (state == 1 || state == 3)) {
                state++;
            } else {
                state = b == '\r' ? 1 : 0;
            }
        }
        String[] lines = head.toString(StandardCharsets.US_ASCII).split("\r\n");
        String[] start = lines[0].split(" ");
        Map<String, String> headers = new TreeMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).strip());
            }
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
        return new Request(start[0], start[1], headers, body);
    }
}
