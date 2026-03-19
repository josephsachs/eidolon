package com.eidolon.game.evennia

import io.vertx.core.json.JsonObject

/**
 * Entity types that support named view projections.
 * Views are behavior (code), not registered state — they work on any node.
 */
interface Viewable {
    fun project(viewName: String): JsonObject?
}
