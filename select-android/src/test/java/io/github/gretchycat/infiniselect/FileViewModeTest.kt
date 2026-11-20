package io.github.gretchycat.infiniselect

import org.junit.Assert.*
import org.junit.Test

class FileViewModeTest {
    @Test
    fun explicitRequestOverridesSavedModeWithoutDisablingUserToggle() {
        assertFalse(FileViewMode.initialIconMode(FileViewMode.LIST, true))
        assertTrue(FileViewMode.initialIconMode(FileViewMode.ICON, false))
        assertTrue(FileViewMode.initialIconMode(null, true))
        assertFalse(FileViewMode.initialIconMode(null, false))
    }

    @Test
    fun wireValuesAreOptionalAndUnknownValuesKeepSavedPreference() {
        assertEquals(FileViewMode.LIST, FileViewMode.fromWire("list"))
        assertEquals(FileViewMode.ICON, FileViewMode.fromWire("ICON"))
        assertNull(FileViewMode.fromWire("unknown"))
        assertTrue(FileViewMode.initialIconMode(FileViewMode.fromWire("unknown"), true))
    }
}
