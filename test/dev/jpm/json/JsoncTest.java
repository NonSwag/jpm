package dev.jpm.json;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsoncTest {
    @Test
    void parsesCommentsAndTrailingCommas() {
        Object v = Jsonc.parse("""
                // leading
                {
                  "a": 1, /* inline */
                  "b": [true, false, null,],
                  "c": { "d": "x\\ny" }, // trailing
                }
                """);
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(new BigDecimal("1"), m.get("a"));
        assertEquals(java.util.Arrays.asList(true, false, null), m.get("b"));
        assertEquals("x\ny", ((Map<?, ?>) m.get("c")).get("d"));
    }

    @Test
    void reportsLineOfErrors() {
        Jsonc.ParseException e = assertThrows(Jsonc.ParseException.class, () -> Jsonc.parse("{\n  \"a\": 1\n  \"b\": 2\n}"));
        assertEquals(3, e.line);
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThrows(Jsonc.ParseException.class, () -> Jsonc.parse("{\"a\":1,\"a\":2}"));
    }

    @Test
    void writesRoundTrip() {
        Object v = Jsonc.parse("{\"a\": [\"x\", \"y\"], \"b\": {\"c\": true}}");
        assertEquals(v, Jsonc.parse(Jsonc.write(v)));
    }

    @Test
    void editKeepsComments() {
        String src = """
                {
                  // the name
                  "name": "x",
                  "runtime": {
                    "a:b": "1.0" // pinned
                  }
                }
                """;
        String added = JsoncEdit.set(src, List.of("runtime"), "c:d", "\"2.0\"");
        assertTrue(added.contains("// the name"));
        assertTrue(added.contains("// pinned"));
        assertEquals(Map.of("a:b", "1.0", "c:d", "2.0"), ((Map<?, ?>) Jsonc.parse(added)).get("runtime"));

        String replaced = JsoncEdit.set(added, List.of("runtime"), "a:b", "\"1.1\"");
        assertEquals("1.1", ((Map<?, ?>) ((Map<?, ?>) Jsonc.parse(replaced)).get("runtime")).get("a:b"));

        String removed = JsoncEdit.remove(replaced, List.of("runtime"), "c:d");
        assertEquals(Map.of("a:b", "1.1"), ((Map<?, ?>) Jsonc.parse(removed)).get("runtime"));
        String removedAll = JsoncEdit.remove(removed, List.of("runtime"), "a:b");
        assertEquals(Map.of(), ((Map<?, ?>) Jsonc.parse(removedAll)).get("runtime"));
        assertTrue(removedAll.contains("// the name"));
    }

    @Test
    void editCreatesMissingObjects() {
        String src = "{\n  \"name\": \"x\"\n}\n";
        String out = JsoncEdit.set(src, List.of("dev"), "l:l", "\"1\"");
        assertEquals(Map.of("l:l", "1"), ((Map<?, ?>) Jsonc.parse(out)).get("dev"));
        assertTrue(out.contains("\"dev\": {\n    \"l:l\": \"1\"\n  }"), out);
        String empty = JsoncEdit.set("{\n  \"runtime\": {}\n}", List.of("runtime"), "a:b", "\"1\"");
        assertEquals(Map.of("a:b", "1"), ((Map<?, ?>) Jsonc.parse(empty)).get("runtime"));
    }
}
