package dev.jpm.maven;

import com.sun.net.httpserver.HttpServer;
import dev.jpm.util.JpmException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A rate limit, a server error or a refusal is not "not found": it is retried and never cached as missing. */
class TransientFailureTest {
    private final String oldHome = System.getProperty("jpm.home");
    private final Consumer<Duration> oldSleeper = MavenRepo.sleeper;
    private final List<Duration> waits = new ArrayList<>();
    private HttpServer server;
    private String base;

    /** Statuses the server answers in turn for every request; 200 serves "content". */
    private final List<Integer> statuses = new ArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();
    private String retryAfter;

    @BeforeEach
    void start(@TempDir Path tmp) throws IOException {
        System.setProperty("jpm.home", tmp.resolve("home").toString());
        MavenRepo.sleeper = waits::add;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            int i = requests.getAndIncrement();
            int status = statuses.isEmpty() ? 404 : statuses.get(Math.min(i, statuses.size() - 1));
            byte[] body = status == 200 ? "content".getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (retryAfter != null && status == 429) ex.getResponseHeaders().add("Retry-After", retryAfter);
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/repo/";
    }

    @AfterEach
    void stop() {
        server.stop(0);
        MavenRepo.sleeper = oldSleeper;
        if (oldHome == null) System.clearProperty("jpm.home"); else System.setProperty("jpm.home", oldHome);
    }

    private MavenRepo repo() {
        // only the test server: Central would answer 404 first and is not needed
        return new MavenRepo(List.of(base), true);
    }

    private static Path missing(Path local) {
        return local.resolveSibling(local.getFileName() + ".missing");
    }

    @Test
    void rateLimitIsRetriedHonouringRetryAfter() throws IOException {
        statuses.addAll(List.of(429, 200));
        retryAfter = "7";
        MavenRepo r = repo();
        Path p = r.require("g", "a", "1.0", null, "jar");
        assertEquals("content", Files.readString(p));
        assertEquals(List.of(Duration.ofSeconds(7)), waits);
    }

    @Test
    void serverErrorsAreRetriedWithBackoff() throws IOException {
        statuses.addAll(List.of(503, 502, 200));
        Path p = repo().require("g", "a", "1.0", null, "jar");
        assertEquals("content", Files.readString(p));
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), waits);
    }

    @Test
    void persistentRateLimitIsNotCachedAsMissing() {
        statuses.add(429);
        MavenRepo r = repo();
        JpmException e = assertThrows(JpmException.class, () -> r.require("g", "a", "1.0", null, "jar"));
        assertTrue(e.getMessage().contains("HTTP 429"), e.getMessage());
        assertTrue(e.getMessage().contains("not a missing artifact"), e.getMessage());
        assertEquals(MavenRepo.ATTEMPTS, requests.get());
        assertFalse(Files.exists(missing(r.local("g", "a", "1.0", null, "jar"))));

        // the next run asks again, and finds it
        statuses.clear();
        statuses.add(200);
        requests.set(0);
        assertNotNull(repo().fetch("g", "a", "1.0", null, "jar"));
    }

    @Test
    void refusalIsNotRetriedNorCached() {
        statuses.add(403);
        MavenRepo r = repo();
        assertNull(r.fetch("g", "a", "1.0", null, "jar"));
        assertEquals(1, requests.get());
        assertFalse(Files.exists(missing(r.local("g", "a", "1.0", null, "jar"))));
    }

    @Test
    void notFoundIsCachedAsMissing() {
        statuses.add(404);
        MavenRepo r = repo();
        assertNull(r.fetch("g", "a", "1.0", null, "jar"));
        assertTrue(Files.exists(missing(r.local("g", "a", "1.0", null, "jar"))));
        JpmException e = assertThrows(JpmException.class, () -> r.require("g", "a", "1.0", null, "jar"));
        assertTrue(e.getMessage().startsWith("Could not find"), e.getMessage());
        assertEquals(1, requests.get(), "the second lookup is answered from the cache");
    }

    @Test
    void retryAfterIsBounded() {
        assertEquals(MavenRepo.MAX_WAIT, MavenRepo.backoff(0, "3600"));
        assertEquals(Duration.ofSeconds(4), MavenRepo.backoff(2, null));
        assertEquals(Duration.ofSeconds(1), MavenRepo.backoff(0, "soon"));
        assertEquals(Duration.ZERO, MavenRepo.backoff(0, "Wed, 21 Oct 2015 07:28:00 GMT"));
    }
}
