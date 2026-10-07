
package com.example;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public class Testing {
    static final String SELENIUM_VERSION = "4.25.0";
    static final String SELENIUM_URL =
        "https://github.com/SeleniumHQ/selenium/releases/download/selenium-" +
        SELENIUM_VERSION + "/selenium-server-" + SELENIUM_VERSION + ".jar";

    static HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30)).build();

    static String request(String method, String url, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json");
        if ("POST".equals(method)) b.POST(HttpRequest.BodyPublishers.ofString(body));
        else if ("DELETE".equals(method)) b.DELETE();
        else b.GET();
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400)
            throw new RuntimeException("HTTP " + r.statusCode() + " from " + url + ": " + r.body());
        return r.body();
    }

    static String jsonString(String s) {
        return "\"" + s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r") + "\"";
    }

    static String extract(String json, String key) {
        String needle = "\"" + key + "\"";
        int p = json.indexOf(needle);
        if (p < 0) return null;
        p = json.indexOf(':', p + needle.length());
        if (p < 0) return null;
        p++;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p < json.length() && json.charAt(p) == '"') {
            int e = p + 1;
            while (e < json.length()) {
                if (json.charAt(e) == '"' && json.charAt(e-1) != '\\') break;
                e++;
            }
            return json.substring(p + 1, e);
        }
        return null;
    }

    static boolean contains(String body, String text) {
        return body != null && body.contains(text);
    }

    static void waitForServer(String base) throws Exception {
        for (int i=0;i<60;i++) {
            try {
                HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/status"))
                        .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() < 500) return;
            } catch (Exception ignored) {}
            Thread.sleep(1000);
        }
        throw new RuntimeException("Selenium Server did not start.");
    }

    static Path downloadServer() throws Exception {
        Path p = Paths.get(System.getProperty("java.io.tmpdir"),
                           "selenium-server-" + SELENIUM_VERSION + ".jar");
        if (Files.exists(p) && Files.size(p) > 10_000_000) return p;

        System.out.println("Downloading Selenium Server " + SELENIUM_VERSION + "...");
        HttpRequest req = HttpRequest.newBuilder(URI.create(SELENIUM_URL))
            .timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() >= 400)
            throw new RuntimeException("Unable to download Selenium Server. HTTP " + res.statusCode());
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(p)) {
            in.transferTo(out);
        }
        return p;
    }

    public static void main(String[] args) {
        String url = System.getProperty("baseUrl",
            System.getenv().getOrDefault("BASE_URL",
                "http://18.61.172.40:8080/testapp/hello"));

        Process server = null;
        String base = "http://127.0.0.1:4444";
        String sessionId = null;

        try {
            Path serverJar = downloadServer();

            System.out.println("Starting Selenium Server...");
            server = new ProcessBuilder(
                "java", "-jar", serverJar.toString(), "standalone", "--port", "4444")
                .redirectErrorStream(true).inheritIO().start();

            waitForServer(base);

            String caps = "{\"capabilities\":{\"alwaysMatch\":{" +
                "\"browserName\":\"chrome\"," +
                "\"goog:chromeOptions\":{\"args\":[\"--headless=new\",\"--no-sandbox\",\"--disable-dev-shm-usage\",\"--window-size=1280,800\"]}" +
                "}}}";

            System.out.println("Starting Chrome WebDriver session...");
            String session = request("POST", base + "/session", caps);
            sessionId = extract(session, "sessionId");
            if (sessionId == null) throw new RuntimeException("No sessionId returned: " + session);

            System.out.println("Opening: " + url);
            request("POST", base + "/session/" + sessionId + "/url",
                    "{\"url\":" + jsonString(url) + "}");

            String source = request("GET", base + "/session/" + sessionId + "/source", null);

            String expected1 = "Hello from Jenkins!";
            String expected2 = "Java WAR successfully deployed to Tomcat 10.";

            if (!contains(source, expected1))
                throw new AssertionError("TEST FAILED: Expected '" + expected1 + "' was not found.");
            if (!contains(source, expected2))
                throw new AssertionError("TEST FAILED: Expected deployment message was not found.");

            System.out.println("TEST PASSED: Selenium verified the Tomcat application.");
        } catch (Throwable t) {
            System.err.println("TEST FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        } finally {
            if (sessionId != null) {
                try { request("DELETE", base + "/session/" + sessionId, null); } catch (Exception ignored) {}
            }
            if (server != null) {
                server.destroy();
                try { server.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
                if (server.isAlive()) server.destroyForcibly();
            }
        }
    }
}
