package com.onedayonemasterpiece.recordideahub

import android.content.Context

object AppGraph {
    @Volatile private var storeInstance: SessionStore? = null
    @Volatile private var configInstance: ConfigStore? = null

    fun store(context: Context): SessionStore = storeInstance ?: synchronized(this) {
        storeInstance ?: SessionStore(context.applicationContext).also { storeInstance = it }
    }

    fun config(context: Context): ConfigStore = configInstance ?: synchronized(this) {
        configInstance ?: ConfigStore(context.applicationContext).also { configInstance = it }
    }
}
