package com.eidolon.game.evennia

import io.vertx.core.json.JsonObject

/**
 * Interface for Minare entities that shadow Evennia objects.
 * Provides the display-ready subset of state that Evennia caches locally.
 *
 * When @State @Mutable fields change via operations, the framework delta broadcast
 * already reaches Evennia. For @Property fields that change on Task ticks,
 * the entity should push updateView() via EvenniaCommUtils.
 */
interface EvenniaShadow {
    fun shadowEvenniaId(): String
    fun updateView(): JsonObject
}
