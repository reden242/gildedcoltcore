package dev.rdbot.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.rdbot.Services;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token-only panel server (JDK HttpServer, no extra dependency).
 *
 * <p>Access rules: every request must carry a valid panel token as the
 * {@code ?token=} query parameter (first visit) or the session cookie set by
 * it (later visits). Anything else gets a plain 401/403 with no hint of what
 * lives here. A successful token visit sets HttpOnly cookies and redirects to
 * the token-free URL. State-changing API calls additionally need the
 * {@code X-CSRF-Token} header to match the CSRF cookie.
 */
public final class PanelServer {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String SESSION_COOKIE = "rdbot_panel";
    private static final String CSRF_COOKIE = "rdbot_csrf";
    private static final int MAX_BODY = 12 * 1024 * 1024;

    private final Services services;
    private final ApiRoutes api;
    private HttpServer server;

    public PanelServer(Services services) {
        this.services = services;
        this.api = new ApiRoutes(services);
    }

    public void start() throws IOException {
        var config = services.config();
        server = HttpServer.create(new InetSocketAddress(
                config.str("panel.bind", "0.0.0.0"), config.intOf("panel.port", 12022)), 64);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "panel");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        System.out.println("[panel] listening on " + baseUrl());
    }

    public String baseUrl() {
        var config = services.config();
        return "http://" + config.str("panel.bind", "0.0.0.0") + ":"
                + config.intOf("panel.port", 12022);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!ipAllowed(ip)) {
                deny(exchange, 403, "Forbidden.");
                return;
            }
            var tokens = services.tokens();
            if (tokens.banned(ip)) {
                deny(exchange, 403, "Forbidden.");
                return;
            }
            String rawPath = exchange.getRequestURI().getRawPath();
            String path = rawPath == null || rawPath.isBlank() ? "/" : rawPath;
            Map<String, String> query = queryParams(exchange.getRequestURI().getRawQuery());
            Map<String, String> cookies = cookies(exchange.getRequestHeaders());

            // Token in the URL: validate, set cookies, strip it via redirect.
            String urlToken = query.get("token");
            if (urlToken != null && !urlToken.isBlank()) {
                if (tokens.valid(urlToken)) {
                    tokens.success(ip);
                    setSession(exchange, urlToken);
                    redirect(exchange, stripToken(exchange.getRequestURI()));
                } else {
                    tokens.failure(ip);
                    deny(exchange, 401, "Unauthorized.");
                }
                return;
            }

            String session = cookies.get(SESSION_COOKIE);
            if (!tokens.valid(session)) {
                if (session != null && !session.isBlank()) tokens.failure(ip);
                deny(exchange, 401, "Unauthorized.");
                return;
            }

            if (path.startsWith("/api/")) {
                if (isMutating(exchange.getRequestMethod())
                        && !csrfOk(cookies, exchange.getRequestHeaders())) {
                    deny(exchange, 403, "Forbidden.");
                    return;
                }
                if ("/api/logout".equals(path)) clearSession(exchange);
                Map<String, Object> body = readBody(exchange);
                var reply = api.route(exchange.getRequestMethod(), path, query, body);
                sendJson(exchange, reply.status(), reply.serialize());
                return;
            }

            serveStatic(exchange, path);
        } catch (Exception e) {
            try {
                deny(exchange, 500, "Internal error.");
            } catch (Exception ignored) {
            }
        }
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        String resource = switch (path) {
            case "/", "/index.html" -> "/panel/index.html";
            case "/app.js" -> "/panel/app.js";
            case "/style.css" -> "/panel/style.css";
            default -> null;
        };
        if (resource == null) {
            deny(exchange, 404, "Not found.");
            return;
        }
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                deny(exchange, 404, "Not found.");
                return;
            }
            bytes = in.readAllBytes();
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(resource) + "; charset=utf-8");
        securityHeaders(headers);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".js")) return "application/javascript";
        if (resource.endsWith(".css")) return "text/css";
        return "text/html";
    }

    private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!isMutating(method) && !"GET".equalsIgnoreCase(method)) return Map.of();
        if (!"GET".equalsIgnoreCase(method) && exchange.getRequestHeaders().getFirst("Content-Length") == null
                && !"chunked".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Transfer-Encoding"))) {
            return Map.of();
        }
        byte[] raw;
        try (InputStream in = exchange.getRequestBody()) {
            raw = in.readNBytes(MAX_BODY + 1);
        }
        if (raw.length == 0) return Map.of();
        if (raw.length > MAX_BODY) throw new IOException("body too large");
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.contains("application/json")) {
            Object parsed = M.readValue(raw, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        }
        return Map.of();
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        securityHeaders(headers);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void setSession(HttpExchange exchange, String token) {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Set-Cookie", SESSION_COOKIE + "=" + token
                + "; Path=/; Max-Age=" + (services.config().intOf("panel.cookie-days", 30) * 86400L)
                + "; HttpOnly; SameSite=Strict");
        headers.add("Set-Cookie", CSRF_COOKIE + "=" + services.tokens().issueCsrf()
                + "; Path=/; Max-Age=" + (services.config().intOf("panel.cookie-days", 30) * 86400L)
                + "; SameSite=Strict");
    }

    private void clearSession(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Set-Cookie", SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        headers.add("Set-Cookie", CSRF_COOKIE + "=; Path=/; Max-Age=0; SameSite=Strict");
    }

    private boolean csrfOk(Map<String, String> cookies, Headers headers) {
        String cookie = cookies.get(CSRF_COOKIE);
        String header = headers.getFirst("X-CSRF-Token");
        return cookie != null && !cookie.isBlank() && cookie.equals(header);
    }

    private static boolean isMutating(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);
    }

    private boolean ipAllowed(String ip) {
        List<String> allow = services.config().strings("panel.ip-allowlist");
        return allow.isEmpty() || allow.contains(ip);
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void deny(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = ("<html><body><h1>" + status + "</h1><p>" + message + "</p></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        securityHeaders(headers);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void securityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'");
    }

    private static String stripToken(java.net.URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) path = "/";
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) return path;
        StringBuilder kept = new StringBuilder();
        for (String part : rawQuery.split("&")) {
            String key = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
            if ("token".equals(key)) continue;
            if (kept.length() > 0) kept.append('&');
            kept.append(part);
        }
        return kept.length() == 0 ? path : path + "?" + kept;
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return out;
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            String value = eq < 0 ? "" : part.substring(eq + 1);
            try {
                out.put(java.net.URLDecoder.decode(key, StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static Map<String, String> cookies(Headers headers) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> raw = headers.get("Cookie");
        if (raw == null) return out;
        for (String header : raw) {
            for (String part : header.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                out.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
            }
        }
        return out;
    }
}
