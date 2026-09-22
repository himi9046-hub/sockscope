package sockscope;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostNamesTest {

    @Test
    void mapsEveryAddressOfAnAnswer() throws IOException {
        var names = new HostNames(100);
        names.add(Sample.parse("""
                {"t":1,"dns":{"name":"example.net","addrs":["8.6.112.0","2a06:98c1:3122:8000::"]}}
                """).dns());

        assertEquals("example.net", names.nameOf("8.6.112.0"));
        assertEquals("example.net", names.nameOf("2a06:98c1:3122:8000::"));
        assertEquals("", names.nameOf("1.1.1.1"));
    }

    @Test
    void laterAnswersWin() {
        var names = new HostNames(100);
        names.add(new Sample.Dns("old.example", List.of("10.0.0.1")));
        names.add(new Sample.Dns("new.example", List.of("10.0.0.1")));
        assertEquals("new.example", names.nameOf("10.0.0.1"));
    }

    @Test
    void leastRecentlyUsedNamesAreForgotten() {
        var names = new HostNames(2);
        names.add(new Sample.Dns("a", List.of("1.0.0.1")));
        names.add(new Sample.Dns("b", List.of("1.0.0.2")));
        names.nameOf("1.0.0.1");
        names.add(new Sample.Dns("c", List.of("1.0.0.3")));

        assertEquals("a", names.nameOf("1.0.0.1"));
        assertEquals("", names.nameOf("1.0.0.2"));
        assertEquals("c", names.nameOf("1.0.0.3"));
    }
}
