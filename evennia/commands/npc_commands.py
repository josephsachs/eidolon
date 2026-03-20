"""
NPC Interaction Commands

Commands that let players interact with NonplayerCharacters.
These merge into a player's cmdset when they share a room with an NPC.
"""

from commands.command import Command


def _get_client():
    from server.conf.minare_client import get_minare_client
    return get_minare_client()


class CmdTalk(Command):
    """
    Talk to an NPC.

    Usage:
      talk <npc> <message>

    Speak to a non-player character in the room.
    """
    key = "talk"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        if not self.args or not self.args.strip():
            self.caller.msg("Talk to whom?")
            return

        args = self.args.strip()

        # Split into target and message
        parts = args.split(None, 1)
        target_name = parts[0]
        text = parts[1] if len(parts) > 1 else ""

        if not text:
            self.caller.msg("What do you want to say?")
            return

        # Find the NPC in the room
        from typeclasses.characters import NonplayerCharacter
        npc = None
        for obj in self.caller.location.contents:
            if isinstance(obj, NonplayerCharacter) and obj.key.lower().startswith(target_name.lower()):
                npc = obj
                break

        if not npc:
            self.caller.msg(f"You don't see '{target_name}' here.")
            return

        npc_minare_id = npc.db.minare_domain_id
        if not npc_minare_id:
            self.caller.msg(f"{npc.key} doesn't seem responsive.")
            return

        # Show the player's speech locally
        self.caller.msg(f'You say to {npc.key}, "{text}"')
        self.caller.location.msg_contents(
            f'{self.caller.key} says to {npc.key}, "{text}"',
            exclude=[self.caller],
        )

        # Send to Minare
        caller_minare_id = self.caller.db.minare_domain_id or ""
        _get_client().send_message({
            "type": "npc_interact",
            "npc_id": npc_minare_id,
            "player_id": caller_minare_id,
            "player_name": self.caller.key,
            "interaction_type": "talk",
            "text": text,
        })
