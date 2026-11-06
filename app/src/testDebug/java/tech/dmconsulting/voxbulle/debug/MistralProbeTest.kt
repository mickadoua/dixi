package tech.dmconsulting.voxbulle.debug

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** The probe passes on the JVM; scripts/mistral-probe.sh runs it in the minified APK. */
class MistralProbeTest {
    @Test
    fun passesWithTheRealClients() = runTest {
        val lines = MistralProbe.run(Files.createTempDirectory("probe").toFile())
        assertEquals(lines.joinToString("\n"), MistralProbe.PASS, lines.last())
        assertEquals(6, lines.count { it.startsWith("OK ") })
    }
}
