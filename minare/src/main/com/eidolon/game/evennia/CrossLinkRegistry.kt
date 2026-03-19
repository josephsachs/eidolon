package com.eidolon.game.evennia

import com.google.inject.Inject
import com.google.inject.Provider
import com.google.inject.Singleton
import com.minare.application.interfaces.AppState
import org.slf4j.LoggerFactory

/**
 * Bidirectional cross-link registry between Evennia objects and Minare entities.
 * Stores mappings in both directions via AppState so any instance can resolve them.
 *
 * Key convention:
 *   CrossLink.{EntityType}.byEvenniaId.{evenniaId} → minareId
 *   CrossLink.{EntityType}.byMinareId.{minareId} → evenniaId
 */
@Singleton
class CrossLinkRegistry @Inject constructor(
    private val appStateProvider: Provider<AppState>
) {
    private val log = LoggerFactory.getLogger(CrossLinkRegistry::class.java)

    suspend fun link(entityType: String, minareId: String, evenniaId: String) {
        val appState = appStateProvider.get()
        appState.set("CrossLink.$entityType.byEvenniaId.$evenniaId", minareId)
        appState.set("CrossLink.$entityType.byMinareId.$minareId", evenniaId)
        log.info("Linked {}: minare={} <-> evennia={}", entityType, minareId, evenniaId)
    }

    suspend fun unlink(entityType: String, minareId: String, evenniaId: String) {
        val appState = appStateProvider.get()
        appState.remove("CrossLink.$entityType.byEvenniaId.$evenniaId")
        appState.remove("CrossLink.$entityType.byMinareId.$minareId")
        log.info("Unlinked {} : minare={} <-> evennia={}", entityType, minareId, evenniaId)
    }

    suspend fun getMinareId(entityType: String, evenniaId: String): String? {
        return appStateProvider.get().get("CrossLink.$entityType.byEvenniaId.$evenniaId")
    }

    suspend fun getEvenniaId(entityType: String, minareId: String): String? {
        return appStateProvider.get().get("CrossLink.$entityType.byMinareId.$minareId")
    }
}
