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

    @Test
    void downloadIsReceivedAndUploadIsSent() {
        var table = new ProcessTable(60);
        table.update(new Sample(100, List.of(proc(7, "curl", 80, 5000, 80, 5000))));

        var row = table.rows().getFirst();
        assertEquals(5000, row.down());
        assertEquals(80, row.up());
        assertEquals(5000, table.down());
        assertEquals(80, table.up());
    }

    @Test
    void busiestComesFirst() {
        var table = new ProcessTable(60);
        table.update(new Sample(100, List.of(
                proc(1, "a", 10, 0, 10, 0),
                proc(2, "b", 0, 900, 0, 900),
                proc(3, "c", 10, 0, 10, 0))));

        assertEquals(List.of(2, 1, 3), table.rows().stream().map(ProcessTable.Row::pid).toList());
    }

    @Test
    void quietProcessesKeepTheirTotalsWithZeroRate() {
        var table = new ProcessTable(60);
        table.update(new Sample(100, List.of(proc(7, "curl", 80, 5000, 80, 5000))));
        table.update(new Sample(101, List.of()));

        var row = table.rows().getFirst();
        assertEquals(0, row.rate());
        assertEquals(5080, row.total());
        assertEquals(0, table.down());
    }

    @Test
    void idleRowsDisappearAfterTheKeepWindow() {
        var table = new ProcessTable(60);
        table.update(new Sample(100, List.of(proc(7, "curl", 1, 1, 1, 1))));
        table.update(new Sample(160, List.of()));
        assertEquals(1, table.rows().size());

        table.update(new Sample(161, List.of()));
        assertTrue(table.rows().isEmpty());
    }

    @Test
    void activeProcessesOutrankIdleOnesWithBiggerTotals() {
        var table = new ProcessTable(60);
        table.update(new Sample(100, List.of(proc(1, "big", 0, 0, 0, 9_000_000), proc(2, "small", 0, 10, 0, 10))));

        assertEquals("small", table.rows().getFirst().comm());
    }
}
