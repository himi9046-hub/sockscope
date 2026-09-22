package sockscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectorClientTest {

    @TempDir
    Path dir;

    @Test
    void readsSamplesAndReconnectsWhenTheCollectorRestarts() throws Exception {
        var socket = dir.resolve("s.sock");
        BlockingQueue<Sample> samples = new LinkedBlockingQueue<>();
        BlockingQueue<Boolean> states = new LinkedBlockingQueue<>();

        try (var client = new CollectorClient(socket, Duration.ofMillis(50), samples::add, states::add)) {
            client.start();
            assertEquals(false, states.poll(2, TimeUnit.SECONDS));

            serveOnce(socket, "{\"t\":1,\"procs\":[{\"pid\":7,\"comm\":\"curl\",\"rx\":10}]}\n");
            assertEquals(1, samples.poll(10, TimeUnit.SECONDS).time());

            serveOnce(socket, "{\"t\":2,\"procs\":[]}\n");
            assertEquals(2, samples.poll(10, TimeUnit.SECONDS).time());
        }
        assertTrue(states.contains(true));
    }

    @Test
    void closingStopsTheClient() throws Exception {
        var client = new CollectorClient(dir.resolve("missing.sock"), Duration.ofSeconds(30), s -> {}, c -> {});
        client.start();
        long started = System.nanoTime();
        client.close();
        assertFalse(Duration.ofNanos(System.nanoTime() - started).toSeconds() > 3);
    }

    private static void serveOnce(Path socket, String line) throws IOException {
        Files.deleteIfExists(socket);
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            try (var conn = server.accept()) {
                conn.write(ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }
}
