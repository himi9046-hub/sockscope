package sockscope;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

public final class CollectorClient implements AutoCloseable {

    private final Path socket;
    private final Duration retry;
    private final Consumer<Sample> onSample;
    private final Consumer<Boolean> onConnected;
    private volatile boolean running = true;
    private volatile SocketChannel channel;
    private Thread thread;

    public CollectorClient(Path socket, Duration retry, Consumer<Sample> onSample, Consumer<Boolean> onConnected) {
        this.socket = socket;
        this.retry = retry;
        this.onSample = onSample;
        this.onConnected = onConnected;
    }

    public void start() {
        thread = Thread.ofVirtual().name("collector-client").start(this::run);
    }

    private void run() {
        while (running) {
            try (var ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                ch.connect(UnixDomainSocketAddress.of(socket));
                channel = ch;
                onConnected.accept(true);
                read(new BufferedReader(Channels.newReader(ch, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            }
            onConnected.accept(false);
            sleep();
        }
    }

    private void read(BufferedReader in) throws IOException {
        String line;
        while (running && (line = in.readLine()) != null) {
            onSample.accept(Sample.parse(line));
        }
    }

    private void sleep() {
        try {
            Thread.sleep(retry);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public void close() throws IOException, InterruptedException {
        running = false;
        if (channel != null) {
            channel.close();
        }
        if (thread != null) {
            thread.interrupt();
            thread.join(Duration.ofSeconds(2));
        }
    }
}
