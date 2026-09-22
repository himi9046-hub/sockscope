package sockscope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sockscope.Sample.Conn;

public final class ConnectionLog {

    public record Entry(long time, Conn conn) {
        public String program() {
            return conn.comm();
        }
    }

    private final int limit;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public ConnectionLog(int limit) {
        this.limit = limit;
    }

    public void add(long time, Conn c) {
        var key = c.pid() + " " + c.local() + ":" + c.lport() + " " + c.remote() + ":" + c.rport();
        var opened = entries.remove(key);
        entries.put(key, new Entry(opened != null && c.closed() ? opened.time() : time, c));
        var it = entries.keySet().iterator();
        while (entries.size() > limit && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public List<Entry> latest(String program) {
        var list = new ArrayList<Entry>();
        for (var e : entries.values()) {
            if (program == null || program.equals(e.program())) {
                list.add(e);
            }
        }
        Collections.reverse(list);
        return list;
    }
}
