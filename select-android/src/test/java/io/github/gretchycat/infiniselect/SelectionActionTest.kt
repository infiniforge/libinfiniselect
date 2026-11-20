package io.github.gretchycat.infiniselect

import org.junit.Assert.*
import org.junit.Test

class SelectionActionTest {
    @Test
    fun callerActionIdentitySurvivesWireRoundTrip() {
        val actions =
            listOf(
                SelectionAction("add_favorite", "Add to favorites"),
                SelectionAction("metadata", "Look up metadata"),
            )
        assertEquals(
            actions,
            SelectionAction.fromWire(actions.map { it.id }, actions.map { it.label }),
        )
    }

    @Test
    fun rejectsAmbiguousAndOversizedCallerActionLists() {
        assertThrows(IllegalArgumentException::class.java) {
            SelectionAction.fromWire(listOf("favorite"), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionAction.validate(
                listOf(SelectionAction("a", "First"), SelectionAction("a", "Second"))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionAction.validate((0..16).map { SelectionAction("a$it", "Action") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionAction("../escape", "Invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionAction("valid", "Invalid\nlabel")
        }
    }
}
