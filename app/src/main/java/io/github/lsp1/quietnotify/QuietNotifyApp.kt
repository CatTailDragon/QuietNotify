package io.github.lsp1.quietnotify

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class QuietNotifyApp : Application(), XposedServiceHelper.OnServiceListener {
    private val mutableService = MutableStateFlow<XposedService?>(null)
    val service = mutableService.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        mutableService.value = service
    }

    override fun onServiceDied(service: XposedService) {
        if (mutableService.value === service) mutableService.value = null
    }
}
