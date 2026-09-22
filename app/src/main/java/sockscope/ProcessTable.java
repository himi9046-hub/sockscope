package sockscope;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProcessTable {

    public static final int HISTORY = 60;

    public record Row(int pid, int count, String comm, long cgroup, long down, long up, long totalDown, long totalUp,
            long lastActive) {
        public long rate() {
            return down + up;
        }

        public long total() {
            return totalDown + totalUp;
        }
    }

    public record Point(long down, long up) {}

    private static final Comparator<Row> BUSIEST = Comparator.comparingLong(Row::rate).reversed()
            .thenComparing(Comparator.comparingLong(Row::total).reversed())
            .thenComparing(Row::comm)
            .thenComparingInt(Row::pid);

    private final long keepSeconds;
    private final Map<Integer, Row> rows = new HashMap<>();
    private final Deque<Point> history = new ArrayDeque<>(HISTORY);

    public ProcessTable(long keepSeconds) {
        this.keepSeconds = keepSeconds;
    }

    public void update(Sample sample) {
        rows.replaceAll((pid, r) -> new Row(pid, 1, r.comm(), r.cgroup(), 0, 0, r.totalDown(), r.totalUp(), r.lastActive()));

        long down = 0;
        long up = 0;
        for (var p : sample.procs()) {
            rows.put(p.pid(), new Row(p.pid(), 1, p.comm(), p.cgroup(), p.rx(), p.tx(), p.rxTotal(), p.txTotal(), sample.time()));
            down += p.rx();
            up += p.tx();
        }
        rows.values().removeIf(r -> sample.time() - r.lastActive() > keepSeconds);

        if (history.size() == HISTORY) {
            history.removeFirst();
        }
        history.addLast(new Point(down, up));
    }

    public List<Row> rows() {
        return rows.values().stream().sorted(BUSIEST).toList();
    }

    public List<Row> byProgram() {
        Map<String, Row> groups = new LinkedHashMap<>();
        for (var r : rows.values()) {
            groups.merge(r.comm(), r, (a, b) -> new Row(Math.min(a.pid(), b.pid()), a.count() + b.count(), a.comm(),
                    a.cgroup() == b.cgroup() ? a.cgroup() : 0, a.down() + b.down(), a.up() + b.up(),
                    a.totalDown() + b.totalDown(), a.totalUp() + b.totalUp(), Math.max(a.lastActive(), b.lastActive())));
        }
        return groups.values().stream().sorted(BUSIEST).toList();
    }

    public List<Point> history() {
        return List.copyOf(history);
    }

    public Point now() {
        return history.isEmpty() ? new Point(0, 0) : history.getLast();
    }
}
