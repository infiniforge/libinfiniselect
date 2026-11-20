package io.github.gretchycat.infiniselect

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ResultReceiver
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** These parcel tests need Android, but do not require Folder, storage access or a surface. */
@RunWith(AndroidJUnit4::class)
class ResultReceiverTransportTest {
    // Deliberately cannot load any InfiniSelect or caller implementation classes.
    private val platformOnlyLoader = object : ClassLoader(null) {}

    @Suppress("DEPRECATION")
    private fun receiveFromOtherApp(args: Bundle, key: String): ResultReceiver {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(args)
            parcel.setDataPosition(0)
            val remote = requireNotNull(parcel.readBundle(platformOnlyLoader))
            requireNotNull(remote.getParcelable<ResultReceiver>(key)).also {
                assertEquals(ResultReceiver::class.java, it.javaClass)
            }
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun browserCallbacksSurvivePlatformOnlyUnmarshallingAndKeepHandlerAndPayloads() {
        val received = CopyOnWriteArrayList<Pair<Int, String?>>()
        val onMain = AtomicBoolean(true)
        val delivered = CountDownLatch(3)
        val callback =
            object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    if (Looper.myLooper() != Looper.getMainLooper()) onMain.set(false)
                    received.add(code to data?.getString("payload"))
                    delivered.countDown()
                }
            }
        val args =
            Bundle().apply { putParcelable(EmbeddedBrowserProtocol.RECEIVER, callback.forIpc()) }
        val remote = receiveFromOtherApp(args, EmbeddedBrowserProtocol.RECEIVER)
        val events =
            listOf(
                EmbeddedBrowserProtocol.NAVIGATION to "directory",
                FileSelectionContract.RESULT_SELECTED to "selected file",
                EmbeddedBrowserProtocol.ERROR to "error details",
            )
        events.forEach { (code, payload) ->
            remote.send(code, Bundle().apply { putString("payload", payload) })
        }
        assertTrue("Callbacks were lost after parceling", delivered.await(5, TimeUnit.SECONDS))
        assertEquals(events, received.toList())
        assertTrue("Callback no longer uses the caller's main handler", onMain.get())
    }

    @Test
    fun openRequestProgressDoesNotExposeTheCallersReceiverSubclass() {
        val delivered = CountDownLatch(1)
        val callback =
            object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    delivered.countDown()
                }
            }
        val args =
            OpenRequest("transport-test", "Transport test", progressReceiver = callback).toBundle()
        receiveFromOtherApp(args, FileSelectionContract.EXTRA_PROGRESS_RECEIVER).send(1, Bundle())
        assertTrue(
            "Progress callback was lost after parceling",
            delivered.await(5, TimeUnit.SECONDS),
        )
    }
}
