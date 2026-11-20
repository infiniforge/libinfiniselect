package io.github.gretchycat.infiniselect

/** Wire contract for a complete browser View hosted and executed by InfiniFolder. */
object EmbeddedBrowserProtocol {
    const val METHOD = "io.github.gretchycat.infiniselect.EMBED_BROWSER"
    const val VERSION = 1
    const val NAVIGATION = 20
    const val ERROR = -20
    const val ACTION = "action"
    const val CREATE = "create"
    const val CLOSE = "close"
    const val OPEN = "open"
    const val RESIZE = "resize"
    const val CONFIGURE = "configure"
    const val BACK = "back"
    const val REFRESH = "refresh"
    const val SESSION = "session"
    const val RECEIVER = "receiver"
    const val LIFETIME = "lifetime"
    const val HOST_TOKEN = "hostToken"
    const val STATE = "browserState"
    const val SERVER = "server"
    const val SURFACE = "surface"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val DISPLAY = "display"
    const val DIRECTORY = "directory"
    const val LABEL = "label"
    const val BREADCRUMBS = "breadcrumbs"
    const val SELECT_LINK_DOCUMENTS = "selectLinkDocuments"
    const val SORT_MODIFIED = "sortModified"
    const val CAN_GO_BACK = "canGoBack"
    const val SOURCE = "source"
}
