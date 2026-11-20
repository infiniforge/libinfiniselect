package io.github.gretchycat.infiniselect

/**
 * Caller-owned file action. The selector displays labels and returns IDs; the caller performs the
 * work.
 */
data class SelectionAction(val id: String, val label: String) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid file action ID" }
        require(label.isNotBlank() && label.length <= 80 && label.none { it.isISOControl() }) {
            "Invalid file action label"
        }
    }

    companion object {
        fun validate(actions: List<SelectionAction>): List<SelectionAction> {
            require(actions.size <= 16 && actions.map { it.id }.distinct().size == actions.size) {
                "Too many or duplicate file actions"
            }
            return actions
        }

        fun fromWire(ids: List<String>, labels: List<String>): List<SelectionAction> {
            require(ids.size <= 16 && ids.size == labels.size) { "Mismatched file actions" }
            return validate(ids.zip(labels) { id, label -> SelectionAction(id, label) })
        }
    }
}
