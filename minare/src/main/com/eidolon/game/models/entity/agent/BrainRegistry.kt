package eidolon.game.models.entity.agent

import com.google.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class BrainRegistry {
    private val log = LoggerFactory.getLogger(BrainRegistry::class.java)
    private val brains = mutableMapOf<String, Brain>()

    fun register(brain: Brain) {
        brains[brain.brainType] = brain
        log.info("Registered brain: {}", brain.brainType)
    }

    fun get(brainType: String): Brain? = brains[brainType]

    fun all(): Map<String, Brain> = brains.toMap()
}
