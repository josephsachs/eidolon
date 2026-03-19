"""
Characters

Characters are (by default) Objects setup to be puppeted by Accounts.
They are what you "see" in game. The Character class in this module
is setup to be the "default" character type created by the default
creation commands.

"""

from evennia.objects.objects import DefaultCharacter
from evennia.utils import logger

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

    def at_object_creation(self):
        super().at_object_creation()
        from commands.default_cmdsets import AgentCharacterCmdSet
        self.cmdset.add(AgentCharacterCmdSet, persistent=True)

    def handle_agent_command(self, command):
        """Dispatch a structured command from Minare."""
        action = command.get('action', '')

        if action == 'say':
            self._execute_in_room(
                command.get('room_evennia_id'),
                f'say {command.get("text", "")}'
            )
        elif action == 'emote':
            self._execute_in_room(
                command.get('room_evennia_id'),
                f'pose {command.get("text", "")}'
            )
        elif action == 'whisper':
            # future
            logger.log_info(f"AgentCharacter: whisper not yet implemented")
        else:
            logger.log_warn(f"AgentCharacter: Unknown action '{action}'")

    def _execute_in_room(self, room_evennia_id, cmd_string):
        """Temporarily move to a room, execute command, return to previous location."""
        from typeclasses.rooms import Room

        if not room_evennia_id:
            logger.log_err("AgentCharacter._execute_in_room: missing room_evennia_id")
            return

        try:
            room = Room.objects.get(id=int(room_evennia_id))
        except (Room.DoesNotExist, ValueError):
            logger.log_err(f"AgentCharacter: Room not found: {room_evennia_id}")
            return

        old_location = self.location
        self.location = room
        self.execute_cmd(cmd_string)
        self.location = old_location


class NonplayerCharacter(Character):
    """
    An NPC. Owns a cmdset (talk, ask, etc.) that merges with a player's
    cmdset on proximity (same room by default, lock-overridable).
    Minare-driven but player-visible. Downstream of the room-history/LLM
    pipeline.
    """

    pass
