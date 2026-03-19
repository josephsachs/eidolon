package eidolon.game.controller

import com.google.inject.Inject
import com.google.inject.Provider
import com.google.inject.Singleton
import com.minare.application.interfaces.AppState
import com.minare.controller.ChannelController

/**
 * Game-specific extension of the framework's ChannelController.
 * Adds application-specific functionality like default channel management.
 */
@Singleton
class GameChannelController @Inject constructor(
    private val appStateProvider: Provider<AppState>
): ChannelController() {
    companion object {
        private const val DEFAULT_CHANNEL_KEY = "Game.defaultChannel"
        private const val SYSTEM_MESSAGE_CHANNEL_KEY = "Game.systemChannel"
    }

    /**
     * Set the default channel ID for this application
     */
    suspend fun setDefaultChannel(channelId: String) {
        appStateProvider.get().set(DEFAULT_CHANNEL_KEY, channelId)
    }

    /**
     * Get the default channel ID for this application
     */
    suspend fun getDefaultChannel(): String? {
        return appStateProvider.get().get(DEFAULT_CHANNEL_KEY)
    }

    /**
     * Set the system channel ID for this application
     */
    suspend fun setSystemMessagesChannel(channelId: String) {
        return appStateProvider.get().set(SYSTEM_MESSAGE_CHANNEL_KEY, channelId)
    }

    /**
     * Set the system channel ID for this application
     */
    suspend fun getSystemMessagesChannel(): String? {
        return appStateProvider.get().get(SYSTEM_MESSAGE_CHANNEL_KEY)
    }
}