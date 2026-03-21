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


_HP_STATUS_COLORS = {
    "HEALTHY":   "|g",
    "SCRATCHED": "|G",
    "BRUISED":   "|y",
    "WOUNDED":   "|Y",
    "INJURED":   "|m",
    "BROKEN":    "|r",
    "CRITICAL":  "|R",
    "DESTROYED": "|[r|x",
}

_HP_STATUS_LABELS = {
    "HEALTHY":   "healthy",
    "SCRATCHED": "scratched",
    "BRUISED":   "bruised",
    "WOUNDED":   "wounded",
    "INJURED":   "injured",
    "BROKEN":    "broken",
    "CRITICAL":  "critical",
    "DESTROYED": "destroyed",
}

_VITAL_COLORS = [
    (80, "|g"),
    (60, "|G"),
    (40, "|y"),
    (20, "|Y"),
    (10, "|r"),
    (0,  "|R"),
]


def _vital_color(value):
    """Return the color code for a vitals value (0-100)."""
    for threshold, color in _VITAL_COLORS:
        if value >= threshold:
            return color
    return "|R"


def _hp_line(label, hp_data):
    """Format one hardpoint line: '  Right Arm    wounded (34)'."""
    status = hp_data.get("status", "HEALTHY")
    hp_val = hp_data.get("hp", 100)
    color = _HP_STATUS_COLORS.get(status, "|n")
    label_str = _HP_STATUS_LABELS.get(status, status.lower())
    return f"  |w{label:<14}|n {color}{label_str:<12}|n ({hp_val:>3})"


def _render_health(health_data):
    """Render health as a formatted body/vitals display."""
    raw_hardpoints = health_data.get("hardpoints", [])
    # Convert list-of-dicts to dict keyed by name
    if isinstance(raw_hardpoints, list):
        hardpoints = {hp["name"]: hp for hp in raw_hardpoints}
    else:
        hardpoints = raw_hardpoints
    w = 76
    title = "  Health  "
    border = "=" * w
    title_l = "=" * 33
    title_r = "=" * 33

    lines = [f"|c+{title_l}{title}{title_r}+|n"]

    # Body hardpoints in anatomical order
    body_layout = [
        ("HEAD",       "Head"),
        ("NECK",       "Neck"),
        ("RIGHT_ARM",  "Right Arm"),
        ("RIGHT_HAND", "Right Hand"),
        ("TORSO",      "Torso"),
        ("LEFT_ARM",   "Left Arm"),
        ("LEFT_HAND",  "Left Hand"),
        ("RIGHT_LEG",  "Right Leg"),
        ("LEFT_LEG",   "Left Leg"),
    ]

    for key, label in body_layout:
        hp_data = hardpoints.get(key, {"hp": 100, "status": "HEALTHY"})
        lines.append(f"|c||n{_hp_line(label, hp_data):<74}|c||n")

    lines.append(f"|c|{'─' * w}||n")

    # Global vitals
    vitals = [
        ("Vitality",      health_data.get("vitality", 100)),
        ("Concentration", health_data.get("concentration", 100)),
        ("Stamina",       health_data.get("stamina", 100)),
        ("Luck",          health_data.get("luck", 100)),
    ]

    for name, val in vitals:
        color = _vital_color(val)
        lines.append(f"|c||n  |w{name:<14}|n {color}{val:>3}|n{'':55}|c||n")

    lines.append(f"|c+{border}+|n")
    return "\n".join(lines)


class CmdHealth(Command):
    """
    View your health status.

    Usage:
      health

    Shows your body condition and vital stats.
    """
    key = "health"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        char_id, _ = _minare_ids(self.caller)
        if not char_id:
            self.caller.msg("No character data available.")
            return

        def on_health(response):
            if response.get('status') != 'success':
                self.caller.msg(
                    f"|rCould not retrieve health: {response.get('error', 'unknown')}|n"
                )
                return

            health = response.get('data', {})
            self.caller.msg("\n" + _render_health(health))

        _get_client().send_with_callback(
            {
                'type': 'entity_query',
                'minare_id': char_id,
                'view': 'health',
            },
            on_health,
        )


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


class CmdExplore(Command):
    """
    Explore a blocked path.

    Usage:
      explore <direction>

    Begin exploring a blocked exit. Progress is made each game tick
    while you remain in the room.
    """
    key = "explore"
    locks = "cmd:all()"
    help_category = "General"

    def func(self):
        if not self.args:
            self.caller.msg("Explore in which direction?")
            return

        direction = self.args.strip().lower()
        char_id, room_id = _minare_ids(self.caller)
        if not char_id or not room_id:
            self.caller.msg("No character data available.")
            return

        def on_response(response):
            if response.get('status') != 'success':
                self.caller.msg(f"|r{response.get('error', 'Could not explore.')}|n")
                return
            progress = response.get('progress', 0)
            threshold = response.get('threshold', 0)
            self.caller.msg(
                f"|yYou begin exploring the path {direction}. "
                f"Progress: {progress}/{threshold}|n"
            )

        _get_client().send_with_callback({
            'type': 'explore',
            'character_id': char_id,
            'room_id': room_id,
            'direction': direction,
        }, on_response)


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


