package com.xiaozhu.zftransfer

import android.util.Log

/** Keeps best-effort fallbacks non-fatal while leaving a trace for diagnosis. */
internal fun <T> Result<T>.logFailure(what: String): Result<T> = onFailure {
    Log.w("ZfTransfer", "$what: ${it.javaClass.simpleName}: ${it.message}")
}
