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
    never seen by players. Has builder-level permissions and executes
    structured commands from Minare's downsocket to effect simulation
    outcomes in Evennia.

    Potentially plural at runtime — different agents can own different
    operational domains or be sharded by region.

    Command dispatch:
      - ACTION_HANDLERS: custom methods for commands needing post-execution
        side effects (dig confirmation, object creation, batch iteration).
      - ACTION_ALIASES: action names that differ from the Evennia command name.
      - ACTION_ASSIGNS: commands using "cmd = text" syntax instead of "cmd text".
      - Default: build command string from action + text, execute via cmdset.
        If room_evennia_id is present, execute in that room's context.
    """

    ACTION_ALIASES = {
        'emote': 'pose',
    }

    ACTION_ASSIGNS = {
        'describe': 'desc',
    }

    ACTION_HANDLERS = {
        'dig': '_handle_dig',
        'create_exit': '_handle_create_exit',
        'batch': '_handle_batch',
    }

    def at_object_creation(self):
        super().at_object_creation()
        from commands.default_cmdsets import AgentCharacterCmdSet
        self.cmdset.add(AgentCharacterCmdSet, persistent=True)
        self.permissions.add("Builder")

    def handle_agent_command(self, command):
        """Dispatch a structured command from Minare."""
        action = command.get('action', '')

        # Custom handler for commands needing side effects
        handler_name = self.ACTION_HANDLERS.get(action)
        if handler_name:
            getattr(self, handler_name)(command)
            return

        # Build command string
        assign_cmd = self.ACTION_ASSIGNS.get(action)
        if assign_cmd:
            cmd_string = f'{assign_cmd} = {command.get("text", "")}'
        else:
            cmd_name = self.ACTION_ALIASES.get(action, action)
            cmd_string = f'{cmd_name} {command.get("text", "")}'

        # Execute with room context if provided, otherwise direct
        room_id = command.get('room_evennia_id')
        if room_id:
            self._execute_in_room(room_id, cmd_string)
        else:
            self.execute_cmd(cmd_string)

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

    def _handle_dig(self, command):
        """Create a new room in Evennia. Sends room_created confirmation back to Minare."""
        from typeclasses.rooms import Room
        from evennia import create_object

        room_key = command.get('room_key', 'New Room')
        description = command.get('description', '')
        scenario_id = command.get('scenario_id', '')

        new_room = create_object(Room, key=room_key)
        new_room.db.desc = description
        if scenario_id:
            new_room.db.scenario_id = scenario_id

        logger.log_info(
            f"AgentCharacter: Dug room '{room_key}' (id={new_room.id}, scenario_id={scenario_id})"
        )

        # Send confirmation back to Minare
        self._send_dig_confirmation(new_room, scenario_id)

    def _handle_create_exit(self, command):
        """Create a one-way exit between two existing rooms."""
        from typeclasses.rooms import Room
        from typeclasses.exits import Exit as EvenniaExit
        from evennia import create_object

        exit_name = command.get('exit_name', '')
        from_id = command.get('from_room_evennia_id')
        to_id = command.get('to_room_evennia_id')

        if not exit_name or not from_id or not to_id:
            logger.log_err(
                f"AgentCharacter._handle_create_exit: missing fields "
                f"(exit_name={exit_name}, from={from_id}, to={to_id})"
            )
            return

        try:
            from_room = Room.objects.get(id=int(from_id))
            to_room = Room.objects.get(id=int(to_id))
            new_exit = create_object(
                EvenniaExit, key=exit_name, location=from_room, destination=to_room
            )
            logger.log_info(
                f"AgentCharacter: Created exit '{exit_name}' "
                f"from '{from_room.key}' -> '{to_room.key}'"
            )
        except (Room.DoesNotExist, ValueError) as e:
            logger.log_err(f"AgentCharacter._handle_create_exit: {e}")

    def _handle_batch(self, command):
        """Execute a list of sub-commands sequentially."""
        commands = command.get('commands', [])
        logger.log_info(f"AgentCharacter: Executing batch of {len(commands)} commands")
        for sub_command in commands:
            self.handle_agent_command(sub_command)

    def _send_dig_confirmation(self, room, scenario_id):
        """Notify Minare that a room was created."""
        from server.conf.minare_client import get_minare_client
        try:
            client = get_minare_client()
            client.send_message({
                "type": "room_created",
                "evennia_id": str(room.id),
                "room_key": room.key,
                "scenario_id": scenario_id,
            })
        except Exception as e:
            logger.log_err(f"AgentCharacter._send_dig_confirmation: {e}")


class NonplayerCharacter(Character):
    """
    An NPC. Owns a cmdset (talk, ask, etc.) that merges with a player's
    cmdset on proximity (same room by default, lock-overridable).
    Minare-driven but player-visible. Downstream of the room-history/LLM
    pipeline.
    """

    pass
