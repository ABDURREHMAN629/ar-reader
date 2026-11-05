package com.ahif.lumen

import android.app.Application
import com.ahif.lumen.di.Graph

class LumenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
