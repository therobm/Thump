package com.therobm.thump

import android.app.Application
import com.therobm.thump.data.ThumpData

class ThumpApplication : Application() {

    // One-per-process ThumpData for the UI process. The MediaLibraryService process constructs
    // its own instance against the same on-disk store. No DI container — explicit construction
    // here is the wiring point.
    lateinit var thumpData: ThumpData
        private set

    override fun onCreate() {
        super.onCreate()
        thumpData = ThumpData(applicationContext)
    }
}
