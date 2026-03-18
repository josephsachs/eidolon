package com.eidolon.game.commands

import com.eidolon.game.GameEntityFactory
import com.eidolon.game.models.entity.Account
import com.google.inject.Inject
import com.google.inject.Provider
import com.google.inject.Singleton
import com.minare.application.interfaces.AppState
import com.minare.controller.EntityController
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class AccountRegister @Inject constructor(
    private val entityController: EntityController,
    private val entityFactory: GameEntityFactory,
    private val appStateProvider: Provider<AppState>
) {
    private val log = LoggerFactory.getLogger(AccountRegister::class.java)

    suspend fun execute(message: JsonObject): JsonObject {
        val evenniaAccountId = message.getString("evennia_account_id")
            ?: return JsonObject().put("status", "error").put("error", "Missing evennia_account_id")

        val appState = appStateProvider.get()
        val existingId = appState.get("Account.byEvenniaId.$evenniaAccountId")

        if (existingId != null) {
            val entities = entityController.findByIds(listOf(existingId))
            val account = entities[existingId]
            if (account != null && account is Account) {
                log.info("Found existing Account for Evennia account {}: {}", evenniaAccountId, existingId)
                return JsonObject()
                    .put("status", "success")
                    .put("account", JsonObject()
                        .put("_id", account._id)
                        .put("evenniaAccountId", account.evenniaAccountId)
                        .put("characterIds", account.characterIds)
                    )
            }
        }

        // Create new Account
        val account = entityFactory.createEntity(Account::class.java) as Account
        account.evenniaAccountId = evenniaAccountId
        entityController.create(account)

        appState.set("Account.byEvenniaId.$evenniaAccountId", account._id!!)

        log.info("Created Account for Evennia account {}: {}", evenniaAccountId, account._id)
        return JsonObject()
            .put("status", "success")
            .put("account", JsonObject()
                .put("_id", account._id)
                .put("evenniaAccountId", account.evenniaAccountId)
                .put("characterIds", account.characterIds)
            )
    }
}
