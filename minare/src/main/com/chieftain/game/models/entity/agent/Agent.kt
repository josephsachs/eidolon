package chieftain.game.models.entity.agent

interface Agent {

    companion object {
        enum class SkillType {
            HUNTING,
            GATHERING,
            MINING,
            QUARRYING,
            WOODCUTTING,
            SCULPTURE,
            JEWELRY,
            SCRIBING,
            MINTING
        }
    }
}