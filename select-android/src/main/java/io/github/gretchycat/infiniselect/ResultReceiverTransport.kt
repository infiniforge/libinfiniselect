package io.github.gretchycat.infiniselect

import android.os.Parcel
import android.os.ResultReceiver

/** Send only Android's parcelable class; the callback implementation stays in its owner. */
internal fun ResultReceiver.forIpc(): ResultReceiver {
    val parcel = Parcel.obtain()
    return try {
        writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        ResultReceiver.CREATOR.createFromParcel(parcel)
    } finally {
        parcel.recycle()
    }
}
