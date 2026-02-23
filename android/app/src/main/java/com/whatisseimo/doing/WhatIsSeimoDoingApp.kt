package com.whatisseimo.doing

import android.app.Application
import com.whatisseimo.doing.data.ServiceGraph

class WhatIsSeimoDoingApp : Application() {
    lateinit var graph: ServiceGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = ServiceGraph(this)
    }
}
