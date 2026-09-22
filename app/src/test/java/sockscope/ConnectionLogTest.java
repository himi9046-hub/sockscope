package sockscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import sockscope.Sample.Conn;

class ConnectionLogTest {

    private static Conn conn(String kind, int pid, String comm, int lport, long ms, long rx) {
        return new Conn(kind, "out", pid, comm, 1, "10.0.0.2", lport, "93.184.216.34", 443, ms, rx, 0);
    }

    @Test
    void closeReplacesItsOpenEntryAndKeepsTheOpenTime() {
        var log = new ConnectionLog(100);
        log.add(100, conn("open", 7, "curl", 50000, 0, 0));
        log.add(103, conn("close", 7, "curl", 50000, 3000, 5000));

        var entries = log.latest(null);
        assertEquals(1, entries.size());
        assertEquals(100, entries.getFirst().time());
        assertTrue(entries.getFirst().conn().closed());
        assertEquals(5000, entries.getFirst().conn().rx());
    }

    @Test
    void newestFirstAndFilteredByProgram() {
        var log = new ConnectionLog(100);
        log.add(100, conn("open", 7, "curl", 50000, 0, 0));
        log.add(101, conn("open", 8, "ssh", 50001, 0, 0));
        log.add(102, conn("open", 9, "curl", 50002, 0, 0));

        assertEquals(List.of(50002, 50001, 50000), log.latest(null).stream().map(e -> e.conn().lport()).toList());
        assertEquals(List.of(50002, 50000), log.latest("curl").stream().map(e -> e.conn().lport()).toList());
    }

    @Test
    void oldestEntriesFallOffAtTheLimit() {
        var log = new ConnectionLog(2);
        log.add(100, conn("open", 1, "a", 1, 0, 0));
        log.add(101, conn("open", 2, "b", 2, 0, 0));
        log.add(102, conn("open", 3, "c", 3, 0, 0));

        assertEquals(List.of("c", "b"), log.latest(null).stream().map(ConnectionLog.Entry::program).toList());
    }

    @Test
    void closeWithoutOpenIsStillShown() {
        var log = new ConnectionLog(10);
        log.add(100, conn("close", 7, "curl", 50000, 0, 0));
        assertEquals(1, log.latest("curl").size());
    }

    @Test
    void parsesAConnectionLine() throws IOException {
        var s = Sample.parse("""
                {"t":1790091275,"conn":{"kind":"close","dir":"in","pid":16649,"comm":"python3","cgroup":21,"local":"127.0.0.1","lport":18095,"remote":"127.0.0.1","rport":54916,"ms":2,"rx":84,"tx":300205}}
                """);

        assertEquals(null, s.procs());
        var c = s.conn();
        assertTrue(c.closed());
        assertTrue(c.inbound());
        assertEquals("python3", c.comm());
        assertEquals(54916, c.rport());
        assertEquals(300205, c.tx());
    }
}
