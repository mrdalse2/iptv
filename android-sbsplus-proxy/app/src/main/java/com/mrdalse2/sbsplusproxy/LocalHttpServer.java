package com.mrdalse2.sbsplusproxy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalHttpServer {
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket server;

    public synchronized void start() throws Exception {
        if (running) return;
        server = new ServerSocket(8787, 32, InetAddress.getByName("0.0.0.0"));
        running = true;
        pool.execute(() -> {
            while (running) {
                try {
                    Socket socket = server.accept();
                    pool.execute(() -> handle(socket));
                } catch (Exception e) {
                    if (running) e.printStackTrace();
                }
            }
        });
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII))) {
            String request = reader.readLine();
            if (request == null) return;
            String[] parts = request.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            if ("/sbsplus.m3u8".equals(path) || "/".equals(path)) {
                try {
                    String media = SbsResolver.resolve();
                    writer.write("HTTP/1.1 302 Found\r\n");
                    writer.write("Location: " + media + "\r\n");
                    writer.write("Cache-Control: no-store, no-cache, must-revalidate\r\n");
                    writer.write("Connection: close\r\n\r\n");
                } catch (Exception e) {
                    byte[] body = ("SBS Plus resolve failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
                    writer.write("HTTP/1.1 502 Bad Gateway\r\n");
                    writer.write("Content-Type: text/plain; charset=utf-8\r\n");
                    writer.write("Content-Length: " + body.length + "\r\n");
                    writer.write("Connection: close\r\n\r\n");
                    writer.flush();
                    socket.getOutputStream().write(body);
                    return;
                }
            } else {
                writer.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
            }
            writer.flush();
        } catch (Exception ignored) {}
    }
}
