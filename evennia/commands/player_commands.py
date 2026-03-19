"""
PlayerCharacter Commands

Command overrides for PlayerCharacter. Each command implements a specific
communication pattern with Minare:
- Disabled: command not available
- Fire-and-forget: local display + send to Minare, no callback
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


