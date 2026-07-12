package com.swag617.restartsched.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.swag617.restartsched.SwagRestartScheduler;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * HTTP handler for the web config editor, mounted under SwagAPI's shared web server at
 * {@code /swagapi/swagrestartscheduler/} via {@link WebEditorModule}.
 *
 * <p>Authentication is handled entirely by SwagAPI's session-cookie system before this
 * handler ever runs — see {@code IWebService#registerModule}. This handler has no
 * password/login logic of its own.</p>
 *
 * <p>Serves the contents of {@code plugins/SwagRestartScheduler/web/config-editor.html}
 * for every path, mirroring the previous standalone server's catch-all {@code "/"}
 * context — the editor is a single-page app with no other routes or static assets.</p>
 */
public class WebEditorHttpHandler implements HttpHandler {

    private final SwagRestartScheduler plugin;

    public WebEditorHttpHandler(SwagRestartScheduler plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        File htmlFile = new File(plugin.getDataFolder(), "web/config-editor.html");
        if (!htmlFile.exists()) {
            sendPlain(exchange, 404, "Editor file not found. Restart the plugin to regenerate it.");
            return;
        }

        byte[] body;
        try {
            body = Files.readAllBytes(htmlFile.toPath());
        } catch (IOException e) {
            sendPlain(exchange, 500, "Failed to read editor file: " + e.getMessage());
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendPlain(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
