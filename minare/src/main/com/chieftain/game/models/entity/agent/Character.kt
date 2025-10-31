package chieftain.game.models.entity.agent

import com.chieftain.game.models.entity.Culture
import com.minare.core.entity.annotations.*

@EntityType("character")
class Character: Agent {
    @State
    var name: String = ""

    @State
    @Mutable
    var culture: Culture.Companion.CultureGroup = Culture.Companion.CultureGroup.UNASSIGNED

    @State
    @Mutable
    var title: CharacterTitle = CharacterTitle.NONE

    @State
    @Mutable
    @Parent
    var kinship: Clan = Clan()

    @State
    @Mutable
    @Child
    var sphere: List<Agent> = listOf()

    @State
    @Mutable
    var location: Pair<Int, Int> = Pair(0, 0)

    companion object {
        enum class CharacterTitle {
            NONE,
            CHIEFTAIN,
            PRINCE,
            GOVERNOR,
            GENERAL
        }
    }
}