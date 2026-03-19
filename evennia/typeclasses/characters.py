"""
Characters

Characters are (by default) Objects setup to be puppeted by Accounts.
They are what you "see" in game. The Character class in this module
is setup to be the "default" character type created by the default
creation commands.

"""

from evennia.objects.objects import DefaultCharacter

from .objects import ObjectParent


class Character(ObjectParent, DefaultCharacter):
    """
    Base Character typeclass. All characters in Eidolon inherit from this.
    """

    pass


class PlayerCharacter(Character):
    """
    A human-controlled character. Puppeted by an Account, subject to the
    5 passthrough sagas (get, drop, give, move, home). Its state is an
    eventually-consistent projection of what Minare pushes via the downsocket.
    """

    def at_object_creation(self):
        """Attach the PlayerCharacter command set."""
        super().at_object_creation()
        from commands.default_cmdsets import PlayerCharacterCmdSet
        self.cmdset.add(PlayerCharacterCmdSet, persistent=True)

    def at_post_unpuppet(self, account, session=None, **kwargs):
        """Inform Minare when player disconnects."""
        super().at_post_unpuppet(account, session=session, **kwargs)
        try:
            from server.conf.minare_client import get_minare_client
            client = get_minare_client()
            room_id = None
            if self.location and hasattr(self.location, 'db'):
                room_id = self.location.db.minare_id
            client.send_message({
                "type": "player_disconnect",
                "character_id": self.db.minare_id or "",
                "room_id": room_id or "",
                "account_id": str(account.id) if account else "",
            })
        except Exception:
            pass


class AgentCharacter(Character):
    """
    A Minare-driven agent inside Evennia. Lives in Limbo, never moves,
    never seen by players. Has a privileged cmdset (builder/moderator)
    and executes structured commands from Minare's downsocket to effect
    simulation outcomes in Evennia.

    Potentially plural at runtime — different agents can own different
    operational domains or be sharded by region.
    """

    pass


class NonplayerCharacter(Character):
    """
    An NPC. Owns a cmdset (talk, ask, etc.) that merges with a player's
    cmdset on proximity (same room by default, lock-overridable).
    Minare-driven but player-visible. Downstream of the room-history/LLM
    pipeline.
    """

    pass
