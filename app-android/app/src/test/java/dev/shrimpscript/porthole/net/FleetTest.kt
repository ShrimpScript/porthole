package dev.shrimpscript.porthole.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Robolectric for org.json, which the plain JVM android.jar stubs out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FleetTest {
    private fun s(id: String, last: String, title: String = id) =
        SessionInfo(id, title, "/srv/$id", "main", last, live = true)

    @Test
    fun oneMachineLeavesRowsUntagged() {
        val m = listOf(Machine("workstation", "workstation", name = "workstation"))
        val merged = Fleet.mergeSessions(m, listOf(listOf(s("a", "2026-09-14T10:00:00Z"))))
        assertEquals("", merged.single().machine)
        assertEquals("", merged.single().machineId)
    }

    @Test
    fun twoMachinesTagAndInterleaveNewestFirst() {
        val m = listOf(Machine("desk", "desk", name = "workstation"), Machine("192.0.2.9", "192.0.2.9", name = "laptop", label = "Laptop"))
        val merged = Fleet.mergeSessions(
            m,
            listOf(
                listOf(s("a", "2026-09-14T10:00:00Z"), s("b", "2026-09-14T08:00:00Z")),
                listOf(s("c", "2026-09-14T09:00:00Z")),
            ),
        )
        assertEquals(listOf("a", "c", "b"), merged.map { it.id })
        assertEquals(listOf("workstation", "Laptop", "workstation"), merged.map { it.machine })
        assertEquals("192.0.2.9", merged[1].machineId)
    }

    @Test
    fun storeRoundTripsAndMirrorsPrefs() {
        val dir = Files.createTempDirectory("fleet").toFile()
        val store = MachineStore(File(dir, "machines.json"))
        assertTrue(store.load().isEmpty())
        // an existing single-machine pairing becomes the first machine
        val first = store.mirrorPrefs(host = "workstation", paired = true)
        assertEquals(1, first.size)
        assertEquals("workstation", first[0].id)
        // a later hello names it; the label the person set is kept
        store.save(listOf(first[0].copy(label = "Desk")))
        val named = store.mirrorPrefs(host = "workstation", paired = true, name = "desk-name-from-hello")
        assertEquals("Desk", named[0].label)
        assertEquals("desk-name-from-hello", named[0].name)
        assertEquals("Desk", named[0].shown)
        // unpairing empties it
        assertTrue(store.mirrorPrefs(host = "workstation", paired = false).isEmpty())
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun shownPrefersLabelThenNameThenHost() {
        assertEquals("192.0.2.9", Machine("192.0.2.9", "192.0.2.9").shown)
        assertEquals("laptop", Machine("192.0.2.9", "192.0.2.9", name = "laptop").shown)
        assertEquals("Mine", Machine("192.0.2.9", "192.0.2.9", name = "laptop", label = "Mine").shown)
    }

    @Test
    fun anUnreachableMachineKeepsItsRowsButNothingOnThemIsLive() {
        val m = listOf(Machine("desk", "desk", name = "workstation"), Machine("lap", "lap", name = "laptop"))
        val merged = Fleet.mergeSessions(
            m,
            listOf(
                listOf(s("a", "2026-09-14T10:00:00Z").copy(working = true, asking = "Which?")),
                listOf(s("c", "2026-09-14T09:00:00Z").copy(working = true, doing = "Bash: ls")),
            ),
            reachable = listOf(true, false),
        )
        val a = merged.first { it.id == "a" }
        val c = merged.first { it.id == "c" }
        assertTrue(a.live && a.working && a.asking == "Which?" && !a.machineDown)
        assertTrue(c.machineDown)
        assertEquals("unreachable", c.machineState)
        assertTrue(!c.live && !c.working && !c.tmux && c.asking.isEmpty() && c.doing.isEmpty())
        assertEquals("laptop", c.machine)
    }

    @Test
    fun addAndRemoveASecondMachine() {
        val dir = Files.createTempDirectory("fleet").toFile()
        val store = MachineStore(File(dir, "machines.json"))
        store.mirrorPrefs(host = "desk", paired = true)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val fleet = Fleet(scope, store)
        assertEquals(listOf("desk"), fleet.machines.value.map { it.id })
        val pairing = fleet.newPairingClient()
        assertTrue(fleet.add(Machine("lap", "lap", name = "laptop"), pairing))
        assertEquals(listOf("desk", "lap"), fleet.machines.value.map { it.id })
        // the same address again is refused, as is the first computer's
        assertTrue(!fleet.add(Machine("lap", "lap"), fleet.newPairingClient()))
        assertTrue(!fleet.add(Machine("desk", "desk"), fleet.newPairingClient()))
        assertEquals(2, fleet.machines.value.size)
        assertTrue(fleet.client("lap") === pairing)
        assertTrue(fleet.client("desk") === fleet.primary)
        assertEquals(listOf("desk", "lap"), store.load().map { it.id })
        // the first machine cannot be removed this way; the second can
        fleet.remove("desk")
        assertEquals(2, fleet.machines.value.size)
        fleet.remove("lap")
        assertEquals(listOf("desk"), fleet.machines.value.map { it.id })
        assertEquals(listOf("desk"), store.load().map { it.id })
        // a prefs mirror keeps the others
        fleet.add(Machine("lap", "lap", name = "laptop"), fleet.newPairingClient())
        fleet.mirrorPrefs("desk", true, name = "desk-name-from-hello")
        assertEquals(listOf("desk", "lap"), fleet.machines.value.map { it.id })
        assertEquals("desk-name-from-hello", fleet.machines.value[0].name)
        // unpairing the first empties the store, second included
        fleet.mirrorPrefs("", false)
        assertTrue(fleet.machines.value.isEmpty())
    }

    @Test
    fun oneSessionResumedTwiceIsOneRow() {
        // What the daemon sent before it collapsed them, and what an older daemon still
        // sends: the same session id twice. Two rows with one key crash a lazy list.
        val m = listOf(Machine("desk", "desk", name = "workstation"))
        val merged = Fleet.mergeSessions(
            m,
            listOf(listOf(s("dup", "2026-09-21T10:00:00Z"), s("dup", "2026-09-21T11:00:00Z"), s("other", "2026-09-21T09:00:00Z"))),
        )
        assertEquals(listOf("dup", "other"), merged.map { it.id })
        assertEquals(1, merged.count { it.id == "dup" })
    }
}
