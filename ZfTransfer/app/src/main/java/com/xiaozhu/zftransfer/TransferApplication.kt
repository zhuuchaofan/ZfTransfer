package com.xiaozhu.zftransfer

import android.app.Application

class TransferApplication : Application() {
    override fun onCreate() { super.onCreate(); AppUi.init(this) }
    val runtime by lazy { ConnectionRuntime(this) }
}
