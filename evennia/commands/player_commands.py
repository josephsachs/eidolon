"""
PlayerCharacter Commands

Command overrides for PlayerCharacter. Each command implements a specific
communication pattern with Minare:
- Disabled: command not available
- Fire-and-forget: local display + send to Minare, no callback
"""

import random
import time
from commands.command import Command

# Skills that always appear in the display, even at zero.
DEFAULT_SKILLS = [
    "Block", "Climbing", "Dancing", "Dodge", "Escape",
    "First Aid", "Gossip", "Haggling", "Hand-to-Hand", "Hiding",
    "Investigation", "Meditating", "Menace", "Pathfinding", "Search", "Swimming",
]

_STATUS_COLORS = {
    "clear":         "|g",
    "learning":      "|G",
    "thinking":      "|y",
    "concentrating": "|Y",
    "muddled":       "|m",
    "murky":         "|r",
    "blocked":       "|R",
}

_STATUS_THRESHOLDS = [
    (20,  "clear"),
    (40,  "learning"),
    (60,  "thinking"),
    (75,  "concentrating"),
    (90,  "muddled"),
    (100, "murky"),
]


def _status_str(status):
    """Convert a status value (float percentage or string) to a canonical label."""
    if isinstance(status, str):
        return status
    for threshold, label in _STATUS_THRESHOLDS:
        if status < threshold:
            return label
    return "blocked"


def _skill_col(name, info):
    """Format one skill entry as a 35-visible-char string for one table column."""
    level = info.get("level", 0.0)
    status = _status_str(info.get("status", 0.0))
    color = _STATUS_COLORS.get(status, "|n")
    # name: 13 visible | space | level: 5 visible | space | (status): 15 visible = 35
    return f"|w{name:<13}|n {level:5.2f} {color}({status:<13})|n"


def _render_skills(skills_data):
    """Render skills as a two-column terminal-width table."""
    merged = {name: {"level": 0.0, "status": "clear"} for name in DEFAULT_SKILLS}
    for name, info in skills_data.items():
        merged[name] = info

    sorted_skills = sorted(merged.items())

    title = "  Skills  "
    border_fill = "=" * 76
    title_fill_l = "=" * 33
    title_fill_r = "=" * 33

    lines = [f"|c+{title_fill_l}{title}{title_fill_r}+|n"]
    for i in range(0, len(sorted_skills), 2):
        left = _skill_col(*sorted_skills[i])
        right = _skill_col(*sorted_skills[i + 1]) if i + 1 < len(sorted_skills) else " " * 35
        lines.append(f"|c||n {left} |c||n {right} |c||n")
    lines.append(f"|c+{border_fill}+|n")

    return "\n".join(lines)


def _get_client():
    """Get the Minare client singleton."""
    from server.conf.minare_client import get_minare_client
    return get_minare_client()


def _minare_ids(caller):
    """Return (character_id, room_id) for the caller."""
    char_id = caller.db.minare_domain_id or ""
    room_id = ""
    if caller.location and hasattr(caller.location, 'db'):
        room_id = caller.location.db.minare_domain_id or ""
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


class CmdSkills(Command):
    """
    View your skills.

    Usage:
      skills

    Shows your current skill levels.
    """
    key = "skills"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        char_id, _ = _minare_ids(self.caller)
        if not char_id:
            self.caller.msg("No character data available.")
            return

        def on_skills(response):
            from evennia.utils import logger
            logger.log_info(f"CmdSkills callback: {response}")
            if response.get('status') != 'success':
                self.caller.msg(
                    f"|rCould not retrieve skills: {response.get('error', 'unknown')}|n"
                )
                return

            skills = response.get('data', {})
            self.caller.msg("\n" + _render_skills(skills))

        _get_client().send_with_callback(
            {
                'type': 'entity_query',
                'minare_id': char_id,
                'view': 'skills',
            },
            on_skills,
        )


class CmdHide(Command):
    """
    Attempt to hide in the current room.

    Usage:
      hide

    Your success depends on your Hiding skill and the room's concealment.
    """
    key = "hide"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        caller = self.caller
        if caller.db.hidden_mod is not None:
            caller.msg("You are already hidden.")
            return

        char_id, _ = _minare_ids(caller)
        if not char_id:
            caller.msg("No character data available.")
            return

        room = caller.location
        concealment = room.concealment

        def on_skills(response):
            if response.get('status') != 'success':
                caller.msg("|rCouldn't check your skills.|n")
                return

            skills = response.get('data', {})
            hiding_info = skills.get('Hiding', {})
            hiding_skill = hiding_info.get('level', 0.0)

            threshold = (hiding_skill + concealment) / 2
            roll = random.randint(0, 99)

            if roll < threshold:
                degree = threshold - roll
                caller.hide(degree)
                caller.msg("|gYou slip into the shadows.|n")
                caller.location.msg_contents(
                    f"|w{caller.key}|n vanishes from sight.",
                    exclude=[caller],
                )
                outcome = "success"
            else:
                caller.msg("|yYou try to hide but can't find good cover.|n")
                outcome = "failure"

            def on_skill_event(event_response):
                if event_response.get('level_up'):
                    caller.msg(
                        f"|gYou have learned something new about Hiding.|n"
                    )

            _get_client().send_with_callback(
                {
                    'type': 'skill_event',
                    'character_id': char_id,
                    'skill_name': 'Hiding',
                    'outcome': outcome,
                },
                on_skill_event
            )

        _get_client().send_with_callback(
            {'type': 'entity_query', 'minare_id': char_id, 'view': 'skills'},
            on_skills,
        )


class CmdSearch(Command):
    """
    Search the room for hidden characters.

    Usage:
      search

    Your success depends on your Search skill versus how well targets are hidden.
    """
    key = "search"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        caller = self.caller
        char_id, _ = _minare_ids(caller)
        if not char_id:
            caller.msg("No character data available.")
            return

        room = caller.location
        hidden_targets = [
            obj for obj in room.contents
            if obj != caller
            and hasattr(obj, 'db')
            and obj.db.hidden_mod is not None
        ]

        if not hidden_targets:
            caller.msg("You search the area but find no one hiding.")
            # Still send the skill event for status gain
            _get_client().send_with_callback(
                {
                    'type': 'skill_event',
                    'character_id': char_id,
                    'skill_name': 'Search',
                    'outcome': 'failure',
                },
                lambda resp: (
                    caller.msg("|gYou have learned something new about Search.|n")
                    if resp.get('level_up') else None
                ),
            )
            return

        def on_skills(response):
            if response.get('status') != 'success':
                caller.msg("|rCouldn't check your skills.|n")
                return

            skills = response.get('data', {})
            search_info = skills.get('Search', {})
            search_skill = search_info.get('level', 0.0)

            found_any = False
            for target in hidden_targets:
                hidden_mod = target.db.hidden_mod or 0
                chance = search_skill - hidden_mod
                roll = random.randint(0, 99)

                if roll < chance:
                    caller.msg(f"|gYou sense |w{target.key}|g hiding nearby.|n")
                    found_any = True

            if not found_any:
                caller.msg("You search carefully but don't find anyone.")

            outcome = "success" if found_any else "failure"

            def on_skill_event(event_response):
                if event_response.get('level_up'):
                    caller.msg(
                        f"|gYou have learned something new about Search.|n"
                    )

            _get_client().send_with_callback(
                {
                    'type': 'skill_event',
                    'character_id': char_id,
                    'skill_name': 'Search',
                    'outcome': outcome,
                },
                on_skill_event,
            )

        _get_client().send_with_callback(
            {'type': 'entity_query', 'minare_id': char_id, 'view': 'skills'},
            on_skills,
        )


class CmdReveal(Command):
    """
    Step out of hiding.

    Usage:
      reveal

    Removes your hidden status, making you visible again.
    """
    key = "reveal"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        caller = self.caller
        if caller.db.hidden_mod is None:
            caller.msg("You aren't hidden.")
            return
        caller.unhide()
        caller.msg("|yYou step out of the shadows.|n")
        caller.location.msg_contents(
            f"|w{caller.key}|n emerges from hiding.",
            exclude=[caller],
        )


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


