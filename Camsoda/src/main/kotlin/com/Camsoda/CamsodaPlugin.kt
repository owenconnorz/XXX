package com.Camsoda

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CamsodaPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CamsodaProvider())
    }
}