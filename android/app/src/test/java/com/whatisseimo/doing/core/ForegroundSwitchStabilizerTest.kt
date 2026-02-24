package com.whatisseimo.doing.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundSwitchStabilizerTest {
    @Test
    fun `switch away and back before 5s does not confirm transient app`() {
        val stabilizer = ForegroundSwitchStabilizer(stableWindowMs = 5_000)

        stabilizer.updateCandidate(packageName = "app.a", firstSeenTs = 1_000)
        stabilizer.updateCandidate(packageName = "app.b", firstSeenTs = 2_000)
        stabilizer.updateCandidate(packageName = "app.a", firstSeenTs = 3_000)

        assertNull(stabilizer.confirm(activePackageName = "app.b"))

        val confirmed = stabilizer.confirm(activePackageName = "app.a")
        assertNotNull(confirmed)
        assertEquals("app.a", confirmed?.packageName)
        assertEquals(3_000L, confirmed?.firstSeenTs)
    }

    @Test
    fun `confirm keeps first-seen timestamp and avoids duplicate emit for same package`() {
        val stabilizer = ForegroundSwitchStabilizer(stableWindowMs = 5_000)

        val firstCandidate = stabilizer.updateCandidate(packageName = "app.b", firstSeenTs = 10_000)
        assertNotNull(firstCandidate)
        assertEquals(15_000L, firstCandidate?.confirmAtTs)

        val firstConfirm = stabilizer.confirm(activePackageName = "app.b")
        assertNotNull(firstConfirm)
        assertEquals(10_000L, firstConfirm?.firstSeenTs)

        val secondCandidate = stabilizer.updateCandidate(packageName = "app.b", firstSeenTs = 20_000)
        assertNotNull(secondCandidate)
        val secondConfirm = stabilizer.confirm(activePackageName = "app.b")
        assertNull(secondConfirm)
    }
}
