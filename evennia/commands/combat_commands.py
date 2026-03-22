"""
Combat Commands

Commands for the combat system: attack, defend, avoid, escape, stance, tactic.
"""

from commands.command import Command


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


class CmdAttack(Command):
    """
    Attack a target, entering combat.

    Usage:
      attack <target>

    Initiates combat with the target. You will continue attacking
    them each turn until you switch modes or leave combat.
    """
    key = "attack"
    locks = "cmd:all()"
    help_category = "Combat"

    def func(self):
        caller = self.caller

        if not self.args:
            caller.msg("Attack whom?")
            return

        char_id, room_id = _minare_ids(caller)
        if not char_id:
            caller.msg("No character data available.")
            return

        target = caller.search(self.args.strip(), location=caller.location)
        if not target:
            return

        if target == caller:
            caller.msg("You can't attack yourself.")
            return

        target_minare_id = getattr(target.db, 'minare_domain_id', None)
        if not target_minare_id:
            caller.msg("You can't attack that.")
            return

        # Lock movement immediately so the player can't walk away before
        # Minare's combat_lock agent command arrives
        caller.db.in_combat = True

        caller.msg(f"|RYou move to attack {target.key}!|n")
        _get_client().send_message({
            'type': 'combat_attack',
            'character_id': char_id,
            'target_id': target_minare_id,
            'room_id': room_id,
        })


class CmdDefend(Command):
    """
    Switch to a defensive combat stance.

    Usage:
      defend

    You gain a significant defensive bonus but only attack
    when you have a clear advantage.
    """
    key = "defend"
    locks = "cmd:all()"
    help_category = "Combat"

    def func(self):
        caller = self.caller
        char_id, _ = _minare_ids(caller)
        if not char_id:
            caller.msg("No character data available.")
            return

        caller.msg("|wYou shift to a defensive posture.|n")
        _get_client().send_message({
            'type': 'combat_mode',
            'character_id': char_id,
            'mode': 'defend',
        })


class CmdAvoid(Command):
    """
    Try to avoid combat and look for an escape.

    Usage:
      avoid

    You focus on defense and build toward escaping combat.
    """
    key = "avoid"
    locks = "cmd:all()"
    help_category = "Combat"

    def func(self):
        caller = self.caller
        char_id, _ = _minare_ids(caller)
        if not char_id:
            caller.msg("No character data available.")
            return

        caller.msg("|yYou look for an opening to disengage.|n")
        _get_client().send_message({
            'type': 'combat_mode',
            'character_id': char_id,
            'mode': 'avoid',
        })


class CmdEscape(Command):
    """
    Attempt to escape from combat.

    Usage:
      escape

    Makes a skill check to break free from combat.
    On success, you are removed from combat and can move again.
    """
    key = "escape"
    locks = "cmd:all()"
    help_category = "Combat"

    def func(self):
        caller = self.caller
        char_id, _ = _minare_ids(caller)
        if not char_id:
            caller.msg("No character data available.")
            return

        def on_escape(response):
            success = response.get('success', False)
            if success:
                caller.msg("|GYou break free and escape!|n")
            else:
                caller.msg("|rYou try to escape but can't break free!|n")

        _get_client().send_with_callback(
            {
                'type': 'combat_escape',
                'character_id': char_id,
            },
            on_escape,
        )


class CmdStance(Command):
    """
    Set your combat stance.

    Usage:
      stance <type>

    Available stances will affect combat in a future update.
    """
    key = "stance"
    locks = "cmd:all()"
    help_category = "Combat"

    def func(self):
        caller = self.caller

        if not self.args:
            current = caller.db.stance or "none"
            caller.msg(f"Current stance: {current}")
            return

        stance = self.args.strip().lower()
        caller.db.stance = stance
        caller.msg(f"|wStance set to: {stance}|n")


class CmdTactic(Command):
    """
    Set your combat tactic.

    Usage:
      tactic <type>

    Available tactics will affect combat in a future update.
    """
    key = "tactic"
    locks = "cmd:all()"
    help_category = "Combat"

    def func(self):
        caller = self.caller

        if not self.args:
            current = caller.db.tactic or "none"
            caller.msg(f"Current tactic: {current}")
            return

        tactic = self.args.strip().lower()
        caller.db.tactic = tactic
        caller.msg(f"|wTactic set to: {tactic}|n")
