package com.xiaozhu.zftransfer

import android.app.Application

class TransferApplication : Application() {
    val runtime by lazy { ConnectionRuntime(this) }
}
