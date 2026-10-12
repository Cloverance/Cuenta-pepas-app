package app.cuentapepas.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AtomicFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun replacesExistingTargetAndLeavesNoTmp() {
        val f = File(tmp.root, "a.json")
        writeAtomic(f, "old".toByteArray())
        writeAtomic(f, "new".toByteArray())
        assertEquals("new", f.readText())
        assertFalse(File(tmp.root, "a.json.tmp").exists())
    }

    @Test fun failedReplaceKeepsOldTargetAndRemovesTmp() {
        // A non-empty directory cannot be replaced by a file: every move fails.
        val target = File(tmp.root, "t").apply { mkdirs() }
        File(target, "keep.txt").writeText("x")
        assertThrows(IOException::class.java) { writeAtomic(target, "new".toByteArray()) }
        assertTrue(File(target, "keep.txt").exists())
        assertFalse(File(tmp.root, "t.tmp").exists())
    }

    @Test fun atomicMoveRefusalIsReportedNotSilent() {
        val target = File(tmp.root, "t2").apply { mkdirs() }
        File(target, "keep.txt").writeText("x")
        val seen = ArrayList<File>()
        val before = AtomicFilesDiagnostics.fallbackCount
        AtomicFilesDiagnostics.listener = { f, _ -> seen += f }
        try {
            assertThrows(IOException::class.java) { writeAtomic(target, "new".toByteArray()) }
        } finally {
            AtomicFilesDiagnostics.listener = null
        }
        assertTrue(AtomicFilesDiagnostics.fallbackCount > before)
        assertEquals(listOf(target), seen)
    }
}
