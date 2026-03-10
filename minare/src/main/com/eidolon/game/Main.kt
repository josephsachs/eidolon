package com.eidolon.game

import com.minare.core.MinareApplication

/**
 * Main entry point for running the Game application
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        MinareApplication.start(GameApplication::class.java, args)
    }
}