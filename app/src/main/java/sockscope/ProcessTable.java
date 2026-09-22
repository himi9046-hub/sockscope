package sockscope;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ProcessTable {

    public record Row(int pid, String comm, long cgroup, long down, long up, long totalDown, long totalUp, long lastActive) {
        public long rate() {
            return down + up;
        }

        public long total() {
            return totalDown + totalUp;
        }
    }

    private static final Comparator<Row> BUSIEST = Comparator.comparingLong(Row::rate).reversed()
            .thenComparing(Comparator.comparingLong(Row::total).reversed())
            .thenComparingInt(Row::pid);

    private final long keepSeconds;
    private final Map<Integer, Row> rows = new HashMap<>();
    private long down;
    private long up;

    public ProcessTable(long keepSeconds) {
        this.keepSeconds = keepSeconds;
    }

    public void update(Sample sample) {
        down = 0;
        up = 0;
        rows.replaceAll((pid, r) -> new Row(pid, r.comm(), r.cgroup(), 0, 0, r.totalDown(), r.totalUp(), r.lastActive()));

        for (var p : sample.procs()) {
            rows.put(p.pid(), new Row(p.pid(), p.comm(), p.cgroup(), p.rx(), p.tx(), p.rxTotal(), p.txTotal(), sample.time()));
            down += p.rx();
            up += p.tx();
        }
        rows.values().removeIf(r -> sample.time() - r.lastActive() > keepSeconds);
    }

    public List<Row> rows() {
        return rows.values().stream().sorted(BUSIEST).toList();
    }

    public long down() {
        return down;
    }

    public long up() {
        return up;
    }
}
