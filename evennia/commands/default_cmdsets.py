"""
Command sets

All commands in the game must be grouped in a cmdset.  A given command
can be part of any number of cmdsets and cmdsets can be added/removed
and merged onto entities at runtime.

To create new commands to populate the cmdset, see
`commands/command.py`.

This module wraps the default command sets of Evennia; overloads them
to add/remove commands from the default lineup. You can create your
own cmdsets by inheriting from them or directly from `evennia.CmdSet`.

"""

from evennia import default_cmds, CmdSet
from commands.player_commands import (
    CmdSay, CmdPose, CmdSkills, CmdHealth, CmdHide, CmdSearch, CmdReveal,
    CmdExplore, CmdNoHome, CmdNoAccess, CmdInfo,
)
from commands.combat_commands import (
    CmdAttack, CmdDefend, CmdAvoid, CmdEscape, CmdStance, CmdTactic,
)
from commands.equipment_commands import (
    CmdEquip, CmdUnequip, CmdInventory,
)
from commands.vendor_commands import (
    CmdBuy, CmdSell,
)


class PlayerCharacterCmdSet(CmdSet):
    """
    Commands specific to PlayerCharacter. Overrides defaults for
    commands that need Minare integration or disabling.
    """

    key = "PlayerCharacterCmdSet"
    priority = 1

    def at_cmdset_creation(self):
        self.add(CmdSay())
        self.add(CmdPose())
        self.add(CmdSkills())
        self.add(CmdHealth())
        self.add(CmdHide())
        self.add(CmdSearch())
        self.add(CmdReveal())
        self.add(CmdExplore())
        self.add(CmdNoHome())
        self.add(CmdNoAccess())
        self.add(CmdAttack())
        self.add(CmdDefend())
        self.add(CmdAvoid())
        self.add(CmdEscape())
        self.add(CmdStance())
        self.add(CmdTactic())
        self.add(CmdEquip())
        self.add(CmdUnequip())
        self.add(CmdInventory())
        self.add(CmdBuy())
        self.add(CmdSell())
        self.add(CmdInfo())


class AgentCharacterCmdSet(CmdSet):
    """
    Commands available to AgentCharacter — privileged set for Minare-driven actions.
    Includes builder commands for room/exit/object creation.
    """

    key = "AgentCharacterCmdSet"
    priority = 1

    def at_cmdset_creation(self):
        from evennia.commands.default.building import (
            CmdDig, CmdCreate, CmdDesc, CmdTeleport, CmdOpen, CmdDestroy
        )
        self.add(CmdSay())
        self.add(CmdPose())
        self.add(CmdDig())
        self.add(CmdCreate())
        self.add(CmdDesc())
        self.add(CmdTeleport())
        self.add(CmdOpen())
        self.add(CmdDestroy())


class NpcCmdSet(CmdSet):
    """
    Commands available when a player is in the same room as an NPC.
    Adds interaction verbs like 'talk'.
    """

    key = "NpcCmdSet"
    priority = 1

    def at_cmdset_creation(self):
        from commands.npc_commands import CmdTalk
        self.add(CmdTalk())


class CharacterCmdSet(default_cmds.CharacterCmdSet):
    """
    The `CharacterCmdSet` contains general in-game commands like `look`,
    `get`, etc available on in-game Character objects. It is merged with
    the `AccountCmdSet` when an Account puppets a Character.
    """

    key = "DefaultCharacter"

    def at_cmdset_creation(self):
        """
        Populates the cmdset
        """
        super().at_cmdset_creation()
        #
        # any commands you add below will overload the default ones.
        #


class AccountCmdSet(default_cmds.AccountCmdSet):
    """
    This is the cmdset available to the Account at all times. It is
    combined with the `CharacterCmdSet` when the Account puppets a
    Character. It holds game-account-specific commands, channel
    commands, etc.
    """

    key = "DefaultAccount"

    def at_cmdset_creation(self):
        """
        Populates the cmdset
        """
        super().at_cmdset_creation()
        #
        # any commands you add below will overload the default ones.
        #


class UnloggedinCmdSet(default_cmds.UnloggedinCmdSet):
    """
    Command set available to the Session before being logged in.  This
    holds commands like creating a new account, logging in, etc.
    """

    key = "DefaultUnloggedin"

    def at_cmdset_creation(self):
        """
        Populates the cmdset
        """
        super().at_cmdset_creation()
        #
        # any commands you add below will overload the default ones.
        #


class SessionCmdSet(default_cmds.SessionCmdSet):
    """
    This cmdset is made available on Session level once logged in. It
    is empty by default.
    """

    key = "DefaultSession"

    def at_cmdset_creation(self):
        """
        This is the only method defined in a cmdset, called during
        its creation. It should populate the set with command instances.

        As and example we just add the empty base `Command` object.
        It prints some info.
        """
        super().at_cmdset_creation()
        #
        # any commands you add below will overload the default ones.
        #
