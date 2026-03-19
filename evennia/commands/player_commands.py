"""
PlayerCharacter Commands

Command overrides for PlayerCharacter. Each command implements a specific
communication pattern with Minare:
- Disabled: command not available
- Fire-and-forget: local display + send to Minare, no callback
- Server-authoritative immediate: send_with_callback, display result
- Server-authoritative passthrough: send_with_callback for ACK, results via downsocket
"""

import time
from commands.command import Command


def _get_client():
    """Get the Minare client singleton."""
    from server.conf.minare_client import get_minare_client
    return get_minare_client()


def _minare_ids(caller):
    """Return (character_id, room_id) for the caller."""
    char_id = caller.db.minare_id or ""
    room_id = ""
    if caller.location and hasattr(caller.location, 'db'):
        room_id = caller.location.db.minare_id or ""
    return char_id, room_id


# ---------------------------------------------------------------------------
# Disabled commands
# ---------------------------------------------------------------------------

class CmdNoHome(Command):
    """
    home (disabled)

    Usage:
      home

    This command is not currently available.
    """
    key = "home"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        self.caller.msg("That command is not currently available.")


class CmdNoAccess(Command):
    """
    access (disabled)

    Usage:
      access

    This command is not currently available.
    """
    key = "access"
    aliases = ["groups", "hierarchy"]
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        self.caller.msg("That command is not currently available.")


# ---------------------------------------------------------------------------
# Fire-and-forget: say, pose
# ---------------------------------------------------------------------------

class CmdSay(Command):
    """
    Speak as your character.

    Usage:
      say <message>
      "<message>

    Say something out loud. Everyone in your current room will hear you.
    """
    key = "say"
    aliases = ['"']
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        if not self.args:
            self.caller.msg("Say what?")
            return

        speech = self.args.strip()

        # Local display via Evennia's built-in say
        self.caller.at_say(speech, msg_self=True)

        # Fire-and-forget to Minare
        char_id, room_id = _minare_ids(self.caller)
        if char_id and room_id:
            _get_client().send_message({
                "type": "room_say",
                "character_id": char_id,
                "room_id": room_id,
                "message": speech,
            })


class CmdPose(Command):
    """
    Perform an emote.

    Usage:
      pose <action>
      :<action>

    Example:
      pose stretches and yawns.
      :stretches and yawns.
    """
    key = "pose"
    aliases = [":", "emote"]
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        if not self.args:
            self.caller.msg("What do you want to do?")
            return

        pose_text = self.args.strip()

        # Local display
        msg = f"{self.caller.key} {pose_text}"
        self.caller.location.msg_contents(msg)

        # Fire-and-forget to Minare
        char_id, room_id = _minare_ids(self.caller)
        if char_id and room_id:
            _get_client().send_message({
                "type": "room_pose",
                "character_id": char_id,
                "room_id": room_id,
                "message": pose_text,
            })


# ---------------------------------------------------------------------------
# Server-authoritative, immediate response: inventory
# ---------------------------------------------------------------------------

class CmdInventory(Command):
    """
    View your inventory.

    Usage:
      inventory
      i
      inv
    """
    key = "inventory"
    aliases = ["i", "inv"]
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        caller = self.caller
        char_id, _ = _minare_ids(caller)

        if not char_id:
            caller.msg("You are not carrying anything.")
            return

        def _on_response(response):
            if response.get("status") != "success":
                caller.msg(f"Error: {response.get('error', 'unknown')}")
                return
            items = response.get("items", [])
            if not items:
                caller.msg("You are not carrying anything.")
            else:
                lines = ["You are carrying:"]
                for item in items:
                    lines.append(f"  {item.get('name', 'unknown')}")
                caller.msg("\n".join(lines))

        _get_client().send_with_callback({
            "type": "inventory_query",
            "character_id": char_id,
        }, _on_response)


# ---------------------------------------------------------------------------
# Server-authoritative, passthrough: get, drop, give
# ---------------------------------------------------------------------------

class CmdGet(Command):
    """
    Pick something up.

    Usage:
      get <object>
      grab <object>
    """
    key = "get"
    aliases = ["grab"]
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        if not self.args:
            self.caller.msg("Get what?")
            return

        target = self.args.strip()
        caller = self.caller
        char_id, room_id = _minare_ids(caller)

        if not char_id:
            caller.msg("You can't do that right now.")
            return

        # Fire-and-forget: results arrive via downsocket entity updates
        _get_client().send_message({
            "type": "command_get",
            "character_id": char_id,
            "room_id": room_id,
            "target": target,
        })


class CmdDrop(Command):
    """
    Drop something you are carrying.

    Usage:
      drop <object>
    """
    key = "drop"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        if not self.args:
            self.caller.msg("Drop what?")
            return

        target = self.args.strip()
        caller = self.caller
        char_id, room_id = _minare_ids(caller)

        if not char_id:
            caller.msg("You can't do that right now.")
            return

        # Fire-and-forget: results arrive via downsocket entity updates
        _get_client().send_message({
            "type": "command_drop",
            "character_id": char_id,
            "room_id": room_id,
            "target": target,
        })


class CmdGive(Command):
    """
    Give something to someone.

    Usage:
      give <object> to <recipient>
      give <object> = <recipient>
    """
    key = "give"
    locks = "cmd:all()"
    help_category = "General"

    def parse(self):
        """Parse 'item to recipient' or 'item = recipient'."""
        self.item = ""
        self.recipient = ""
        args = self.args.strip()

        if " to " in args:
            parts = args.split(" to ", 1)
            self.item = parts[0].strip()
            self.recipient = parts[1].strip()
        elif "=" in args:
            parts = args.split("=", 1)
            self.item = parts[0].strip()
            self.recipient = parts[1].strip()
        else:
            self.item = args

    def func(self):
        if not self.item:
            self.caller.msg("Give what?")
            return
        if not self.recipient:
            self.caller.msg("Give to whom?")
            return

        caller = self.caller
        char_id, room_id = _minare_ids(caller)

        if not char_id:
            caller.msg("You can't do that right now.")
            return

        # Fire-and-forget: results arrive via downsocket entity updates
        _get_client().send_message({
            "type": "command_give",
            "character_id": char_id,
            "room_id": room_id,
            "target": self.item,
            "recipient": self.recipient,
        })
