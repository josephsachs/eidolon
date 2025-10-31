package com.chieftain.game

import com.chieftain.game.GameApplication
import com.minare.core.MinareApplication
import org.slf4j.LoggerFactory

/**
 * Main entry point for running the Game application
 */
object Main {
    private val log = LoggerFactory.getLogger(Main::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        log.info("Starting CHIEFTAIN Game Application")


        MinareApplication.start(GameApplication::class.java, args)
    }
}