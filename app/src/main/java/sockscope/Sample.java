package sockscope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Sample(@JsonProperty("t") long time, List<Proc> procs) {

    private static final ObjectMapper JSON = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Proc(int pid, String comm, long cgroup, long tx, long rx, long txTotal, long rxTotal) {}

    public static Sample parse(String line) throws IOException {
        return JSON.readValue(line, Sample.class);
    }
}
