"""
NPC Interaction Commands

Commands that let players interact with NonplayerCharacters.
These merge into a player's cmdset when they share a room with an NPC.
"""

from commands.command import Command


def _get_client():
    from server.conf.minare_client import get_minare_client
    return get_minare_client()


def _find_npc(caller, target_name):
    """Find an NPC in the caller's room by name prefix."""
    from typeclasses.characters import NonplayerCharacter
    for obj in caller.location.contents:
        if isinstance(obj, NonplayerCharacter) and obj.key.lower().startswith(target_name.lower()):
            return obj
    return None


def _minare_ids(caller):
    """Return (character_id, room_id) for the caller."""
    char_id = caller.db.minare_domain_id or ""
    room_id = ""
    if caller.location and hasattr(caller.location, 'db'):
        room_id = caller.location.db.minare_domain_id or ""
    return char_id, room_id


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

        npc = _find_npc(self.caller, target_name)
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


class CmdAsk(Command):
    """
    Ask an NPC about a topic.

    Usage:
      ask <npc>                - list what they'll talk about
      ask <npc> about <topic>  - ask about something specific

    Ask a non-player character about a specific subject.
    """
    key = "ask"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        if not self.args or not self.args.strip():
            self.caller.msg("Ask whom about what?")
            return

        args = self.args.strip()

        # If no "about", treat entire args as NPC name and list topics
        if " about " not in args.lower():
            target_name = args
            npc = _find_npc(self.caller, target_name)
            if not npc:
                self.caller.msg(f"You don't see '{target_name}' here.")
                return

            sim_state = npc.db.sim_state or {}
            topics = sim_state.get("askTopics", [])
            if not topics:
                self.caller.msg(f"{npc.key} doesn't seem to have much to say.")
                return

            topic_list = ", ".join(sorted(topics))
            self.caller.msg(
                f"You could ask {npc.key} about: |w{topic_list}|n"
            )
            return

        idx = args.lower().index(" about ")
        target_name = args[:idx].strip()
        topic = args[idx + 7:].strip()

        if not target_name:
            self.caller.msg("Ask whom?")
            return
        if not topic:
            self.caller.msg("Ask about what?")
            return

        npc = _find_npc(self.caller, target_name)
        if not npc:
            self.caller.msg(f"You don't see '{target_name}' here.")
            return

        npc_minare_id = npc.db.minare_domain_id
        if not npc_minare_id:
            self.caller.msg(f"{npc.key} doesn't seem responsive.")
            return

        self.caller.msg(f'You ask {npc.key} about {topic}.')
        self.caller.location.msg_contents(
            f'{self.caller.key} asks {npc.key} about {topic}.',
            exclude=[self.caller],
        )

        caller_minare_id = self.caller.db.minare_domain_id or ""
        _get_client().send_message({
            "type": "npc_interact",
            "npc_id": npc_minare_id,
            "player_id": caller_minare_id,
            "player_name": self.caller.key,
            "interaction_type": "ask",
            "topic": topic,
            "text": topic,
        })


class CmdGossip(Command):
    """
    Start gossiping with NPCs in the room.

    Usage:
      gossip

    Settle in and start working the room for information.
    While gossiping, you'll periodically engage NPCs in
    conversation and may learn things they know. Your social
    stats and Gossip skill affect what you can extract.
    """
    key = "gossip"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        char_id, room_id = _minare_ids(self.caller)
        if not char_id or not room_id:
            self.caller.msg("No character data available.")
            return

        caller = self.caller

        def on_response(response):
            if response.get('status') != 'success':
                caller.msg(
                    f"|r{response.get('error', 'Could not start gossiping.')}|n"
                )
                return

            npc_names = response.get('npc_names', 'the locals')
            caller.msg(
                f"|gYou start working the room, chatting up {npc_names}.|n"
            )
            caller.location.msg_contents(
                f"|w{caller.key}|n settles in and starts making conversation.",
                exclude=[caller],
            )

        _get_client().send_with_callback(
            {
                'type': 'gossip_start',
                'character_id': char_id,
                'room_id': room_id,
            },
            on_response,
        )


class CmdStopGossip(Command):
    """
    Stop gossiping.

    Usage:
      stop gossiping

    Disengage from the gossip activity.
    """
    key = "stop gossiping"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        char_id, room_id = _minare_ids(self.caller)
        if not char_id:
            self.caller.msg("No character data available.")
            return

        caller = self.caller

        def on_response(response):
            if response.get('status') != 'success':
                caller.msg(
                    f"|r{response.get('error', 'Could not stop gossiping.')}|n"
                )
                return

            caller.msg("|yYou stop gossiping and turn your attention elsewhere.|n")
            caller.location.msg_contents(
                f"|w{caller.key}|n disengages from the conversation.",
                exclude=[caller],
            )

        _get_client().send_with_callback(
            {
                'type': 'gossip_stop',
                'character_id': char_id,
            },
            on_response,
        )
