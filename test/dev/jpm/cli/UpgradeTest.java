package dev.jpm.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeTest {
    @Test
    void readsChecksums() {
        String sums = "aaa  jpm-linux-x64\nbbb  jpm-linux-arm64\nccc *jpm-windows-x64.exe\n";
        assertEquals("aaa", Upgrade.checksum(sums, "jpm-linux-x64"));
        assertEquals("ccc", Upgrade.checksum(sums, "jpm-windows-x64.exe"));
        assertNull(Upgrade.checksum(sums, "jpm-macos-x64"));
    }

    @Test
    void namesTheAssetForThisPlatform() {
        String a = Upgrade.asset();
        assertTrue(a.matches("jpm-(linux|macos|windows)-(x64|arm64|[a-z0-9]+)(\\.exe)?"), a);
    }

    @Test
    void releaseUrlsUsePlainVersionTags() {
        assertEquals("https://github.com/NonSwag/jpm/releases/download/0.3.0", Upgrade.base("0.3.0"));
        assertEquals("https://github.com/NonSwag/jpm/releases/latest/download", Upgrade.base(null));
    }
}
