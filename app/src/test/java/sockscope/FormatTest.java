package sockscope;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FormatTest {

    @ParameterizedTest
    @CsvSource({"0, 0 B", "1023, 1023 B", "1024, 1.0 KB", "1536, 1.5 KB", "10240, 10 KB", "5368709120, 5.0 GB"})
    void formatsByteCounts(long bytes, String expected) {
        assertEquals(expected, Bytes.format(bytes));
    }

    @Test
    void idleRateIsBlank() {
        assertEquals("", Bytes.rate(0));
        assertEquals("2.0 KB/s", Bytes.rate(2048));
    }

    @Test
    void parsesACollectorLineAndIgnoresUnknownFields() throws IOException {
        var s = Sample.parse("""
                {"t":1790087939,"extra":true,"procs":[{"pid":8086,"comm":"python3","cgroup":21,"tx":2000205,"rx":83,"txTotal":2000205,"rxTotal":83,"new":1}]}
                """);

        assertEquals(1790087939, s.time());
        var p = s.procs().getFirst();
        assertEquals(8086, p.pid());
        assertEquals("python3", p.comm());
        assertEquals(2000205, p.tx());
        assertEquals(83, p.rxTotal());
    }
}
