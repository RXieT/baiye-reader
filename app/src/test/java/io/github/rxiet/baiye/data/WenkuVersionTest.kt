package io.github.rxiet.baiye.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class WenkuVersionTest {
    @Test fun protocolUsesMinuteWindowsAndStableHexSlice() {
        // A synthetic value, unrelated to the private build configuration.
        assertEquals("1.30-test-fixture-46a5957e", WenkuVersion.value("test-fixture", 60000))
        assertEquals(WenkuVersion.value("test-fixture", 60000), WenkuVersion.value("test-fixture", 60059))
    }
    @Test(expected = IOException::class) fun unconfiguredBuildDoesNotPretendToSupportRelay() { WenkuVersion.value("") }
}
