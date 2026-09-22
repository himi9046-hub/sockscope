package sockscope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Sample(@JsonProperty("t") long time, List<Proc> procs, Conn conn, Dns dns) {

    private static final ObjectMapper JSON = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Proc(int pid, String comm, long cgroup, long tx, long rx, long txTotal, long rxTotal) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Conn(String kind, String dir, int pid, String comm, long cgroup, String local, int lport,
            String remote, int rport, long ms, long rx, long tx) {

        public boolean closed() {
            return "close".equals(kind);
        }

        public boolean inbound() {
            return "in".equals(dir);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dns(String name, List<String> addrs) {}

    public static Sample parse(String line) throws IOException {
        return JSON.readValue(line, Sample.class);
    }
}
