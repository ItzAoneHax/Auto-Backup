package com.autobackup;

import com.autobackup.scheduler.DaemonConsole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DaemonConsoleTest {

    @Test
    void splitsPlainArgsOnWhitespace() {
        assertEquals(List.of("run", "/data/a", "/data/b"), DaemonConsole.tokenize("run /data/a /data/b"));
        assertEquals(List.of("status"), DaemonConsole.tokenize("  status  "));
    }

    @Test
    void keepsQuotedPathsWithSpacesAndUnicodeTogether() {
        assertEquals(List.of("run", "/nvme/[7777]生存服", "/nvme/[8089] 生存测试"),
                DaemonConsole.tokenize("run /nvme/[7777]生存服 \"/nvme/[8089] 生存测试\""));
        assertEquals(List.of("run", "/a b/c"),
                DaemonConsole.tokenize("run '/a b/c'"));
    }

    @Test
    void treatsUnclosedQuoteRemainderAsSingleArg() {
        assertEquals(List.of("run", "/a b c"), DaemonConsole.tokenize("run \"/a b c"));
    }

    @Test
    void ignoresQuotesAroundPathsWithoutSpaces() {
        assertEquals(List.of("run", "/plain/path"), DaemonConsole.tokenize("run '/plain/path'"));
    }
}
