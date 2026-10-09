package com.hurma.mcpbuilder.mcp;

import com.hurma.mcpbuilder.McpBuilderMod;
import com.hurma.mcpbuilder.config.McpConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Minimal HTTP/1.1 server implementing the MCP "Streamable HTTP" transport (JSON responses only).
 * Built on plain sockets so it works on any Java runtime bundled with Minecraft.
 */
public class McpHttpServer {
    private static final int MAX_BODY = 64 * 1024 * 1024;

    private final String host;
    private final int port;
    private final McpProtocolHandler handler;
    private ServerSocket serverSocket;
    private ExecutorService workers;
    private volatile boolean running;

    public McpHttpServer(String host, int port, McpProtocolHandler handler) {
        this.host = host;
        this.port = port;
        this.handler = handler;
    }

    public void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(host), port));
        serverSocket = socket;
        workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MCP-Builder-HTTP");
            t.setDaemon(true);
            return t;
        });
        running = true;
        Thread accept = new Thread(this::acceptLoop, "MCP-Builder-Accept");
        accept.setDaemon(true);
        accept.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (workers != null) workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setSoTimeout(30_000);
                workers.execute(() -> handle(client));
            } catch (RejectedExecutionException e) {
                break;
            } catch (IOException e) {
                if (running) McpBuilderMod.LOGGER.warn("MCP accept failed: {}", e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                send(out, 400, "text/plain", "Bad Request", null);
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(Locale.ROOT), line.substring(idx + 1).trim());
                }
            }

            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            int length = 0;
            String contentLength = headers.get("content-length");
            if (contentLength != null) {
                try {
                    length = Integer.parseInt(contentLength.trim());
                } catch (NumberFormatException e) {
                    send(out, 400, "text/plain", "Bad Content-Length", null);
                    return;
                }
            }
            if (length < 0 || length > MAX_BODY) {
                send(out, 413, "text/plain", "Payload Too Large", null);
                return;
            }
            byte[] body = in.readNBytes(length);

            if (method.equals("OPTIONS")) {
                send(out, 204, null, "", null);
                return;
            }
            if (path.equals("/health")) {
                send(out, 200, "application/json", "{\"status\":\"ok\"}", null);
                return;
            }
            if (!path.equals("/mcp") && !path.equals("/mcp/") && !path.equals("/")) {
                send(out, 404, "text/plain", "Not Found. MCP endpoint is /mcp", null);
                return;
            }
            if (!authorized(headers, query)) {
                send(out, 401, "application/json", "{\"error\":\"unauthorized\"}", null);
                return;
            }

            switch (method) {
                case "POST" -> {
                    String response = handler.handle(new String(body, StandardCharsets.UTF_8));
                    if (response == null) {
                        send(out, 202, null, "", null);
                    } else {
                        send(out, 200, "application/json", response, null);
                    }
                }
                case "DELETE" -> send(out, 200, "text/plain", "", null);
                default -> send(out, 405, "text/plain", "Use POST for MCP requests", "POST, DELETE, OPTIONS");
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException e) {
            McpBuilderMod.LOGGER.debug("MCP connection error: {}", e.getMessage());
        } catch (Exception e) {
            McpBuilderMod.LOGGER.warn("MCP request failed", e);
        }
    }

    private static boolean authorized(Map<String, String> headers, String query) {
        String token = McpConfig.get().authToken;
        if (token == null || token.isEmpty()) return true;
        String auth = headers.get("authorization");
        if (auth != null && auth.equals("Bearer " + token)) return true;
        String apiKey = headers.get("x-api-key");
        if (apiKey != null && apiKey.equals(token)) return true;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals("token")) {
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (value.equals(token)) return true;
            }
        }
        return false;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
            if (buf.size() > 16384) throw new IOException("Header line too long");
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static void send(OutputStream out, int status, String contentType, String body, String allow) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(bytes.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: Content-Type, Authorization, Accept, Mcp-Session-Id, Mcp-Protocol-Version, X-Api-Key\r\n");
        sb.append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n");
        if (allow != null) sb.append("Allow: ").append(allow).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Payload Too Large";
            default -> "Error";
        };
    }
}
