package sockscope;

import java.util.LinkedHashMap;
import java.util.Map;

public final class HostNames {

    private final Map<String, String> names;

    public HostNames(int limit) {
        names = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > limit;
            }
        };
    }

    public void add(Sample.Dns dns) {
        for (var addr : dns.addrs()) {
            names.put(addr, dns.name());
        }
    }

    public String nameOf(String addr) {
        return names.getOrDefault(addr, "");
    }
}
