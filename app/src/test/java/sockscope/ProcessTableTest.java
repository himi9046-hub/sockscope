package sockscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import sockscope.Sample.Proc;

class ProcessTableTest {

    private static Proc proc(int pid, String comm, long tx, long rx, long txTotal, long rxTotal) {
        return new Proc(pid, comm, 1, tx, rx, txTotal, rxTotal);
    }

    private static Sample sample(long time, Proc... procs) {
        return new Sample(time, List.of(procs), null, null);
    }

    @Test
    void downloadIsReceivedAndUploadIsSent() {
        var table = new ProcessTable(60);
        table.update(sample(100, proc(7, "curl", 80, 5000, 80, 5000)));

        var row = table.rows().getFirst();
        assertEquals(5000, row.down());
        assertEquals(80, row.up());
        assertEquals(new ProcessTable.Point(5000, 80), table.now());
    }

    @Test
    void busiestComesFirst() {
        var table = new ProcessTable(60);
        table.update(sample(100,
                proc(1, "a", 10, 0, 10, 0),
                proc(2, "b", 0, 900, 0, 900),
                proc(3, "c", 10, 0, 10, 0)));

        assertEquals(List.of(2, 1, 3), table.rows().stream().map(ProcessTable.Row::pid).toList());
    }

    @Test
    void quietProcessesKeepTheirTotalsWithZeroRate() {
        var table = new ProcessTable(60);
        table.update(sample(100, proc(7, "curl", 80, 5000, 80, 5000)));
        table.update(sample(101));

        var row = table.rows().getFirst();
        assertEquals(0, row.rate());
        assertEquals(5080, row.total());
        assertEquals(new ProcessTable.Point(0, 0), table.now());
    }

    @Test
    void idleRowsDisappearAfterTheKeepWindow() {
        var table = new ProcessTable(60);
        table.update(sample(100, proc(7, "curl", 1, 1, 1, 1)));
        table.update(sample(160));
        assertEquals(1, table.rows().size());

        table.update(sample(161));
        assertTrue(table.rows().isEmpty());
    }

    @Test
    void activeProcessesOutrankIdleOnesWithBiggerTotals() {
        var table = new ProcessTable(60);
        table.update(sample(100, proc(1, "big", 0, 0, 0, 9_000_000), proc(2, "small", 0, 10, 0, 10)));

        assertEquals("small", table.rows().getFirst().comm());
    }

    @Test
    void groupsProcessesOfTheSameProgram() {
        var table = new ProcessTable(60);
        table.update(sample(100,
                proc(10, "chrome", 100, 1000, 100, 1000),
                proc(11, "chrome", 50, 500, 50, 500),
                proc(20, "curl", 0, 10, 0, 10)));

        var chrome = table.byProgram().getFirst();
        assertEquals("chrome", chrome.comm());
        assertEquals(2, chrome.count());
        assertEquals(10, chrome.pid());
        assertEquals(1500, chrome.down());
        assertEquals(150, chrome.up());
        assertEquals(2, table.byProgram().size());
        assertEquals(3, table.rows().size());
    }

    @Test
    void historyKeepsTheLastMinute() {
        var table = new ProcessTable(600);
        for (int i = 0; i < 90; i++) {
            table.update(sample(100 + i, proc(1, "a", i, 2 * i, i, 2 * i)));
        }

        var history = table.history();
        assertEquals(ProcessTable.HISTORY, history.size());
        assertEquals(new ProcessTable.Point(60, 30), history.getFirst());
        assertEquals(new ProcessTable.Point(178, 89), history.getLast());
    }
}
