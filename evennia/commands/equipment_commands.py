"""
Equipment Commands

Commands for equipping and unequipping items.
"""

from commands.command import Command


def _get_client():
    """Get the Minare client singleton."""
    from server.conf.minare_client import get_minare_client
    return get_minare_client()


class CmdEquip(Command):
    """
    Equip an item from your inventory.

    Usage:
      equip <item>

    Equips the item into its designated slot. The item must be
    in your inventory and have an equipment slot.
    """
    key = "equip"
    locks = "cmd:all()"
    help_category = "Equipment"

    def func(self):
        caller = self.caller

        if not self.args:
            caller.msg("Equip what?")
            return

        char_id = caller.db.minare_domain_id or ""
        if not char_id:
            caller.msg("No character data available.")
            return

        item = caller.search(self.args.strip(), location=caller)
        if not item:
            return

        template_id = getattr(item.db, 'template_id', None)
        if not template_id:
            caller.msg("You can't equip that.")
            return

        def on_equip(response):
            success = response.get('success', False)
            if success:
                slot = response.get('slot', '')
                caller.msg(f"|gYou equip {item.key}.|n")
            else:
                reason = response.get('reason', 'unknown')
                caller.msg(f"|rYou can't equip that: {reason}|n")

        _get_client().send_with_callback(
            {
                'type': 'equip_item',
                'character_id': char_id,
                'template_id': template_id,
                'item_evennia_id': str(item.id),
            },
            on_equip,
        )


class CmdUnequip(Command):
    """
    Unequip an item, returning it to your inventory.

    Usage:
      unequip <slot or item>

    Removes the equipped item from the given slot.
    """
    key = "unequip"
    locks = "cmd:all()"
    help_category = "Equipment"

    def func(self):
        caller = self.caller

        if not self.args:
            caller.msg("Unequip what?")
            return

        char_id = caller.db.minare_domain_id or ""
        if not char_id:
            caller.msg("No character data available.")
            return

        target = self.args.strip()

        def on_unequip(response):
            success = response.get('success', False)
            if success:
                item_name = response.get('item_name', target)
                caller.msg(f"|gYou unequip {item_name}.|n")
            else:
                reason = response.get('reason', 'nothing equipped there')
                caller.msg(f"|r{reason}|n")

        _get_client().send_with_callback(
            {
                'type': 'unequip_item',
                'character_id': char_id,
                'slot_or_item': target,
            },
            on_unequip,
        )


class CmdInventory(Command):
    """
    View your inventory and equipped items.

    Usage:
      inventory

    Shows what you're carrying and what you have equipped.
    """
    key = "inventory"
    aliases = ["inv", "i"]
    locks = "cmd:all()"
    help_category = "Equipment"

    def func(self):
        caller = self.caller
        items = [obj for obj in caller.contents if hasattr(obj.db, 'template_id')]
        other = [obj for obj in caller.contents if not hasattr(obj.db, 'template_id') and obj != caller]

        if not items and not other:
            caller.msg("You aren't carrying anything.")
            return

        lines = ["|wYou are carrying:|n"]
        for item in items:
            lines.append(f"  {item.key}")
        for obj in other:
            lines.append(f"  {obj.key}")

        # Show equipment from sim_state if available
        sim_state = caller.db.sim_state or {}
        equipment = sim_state.get('equipment', {})
        if equipment:
            lines.append("|wEquipped:|n")
            for slot, template_id in equipment.items():
                if template_id:
                    lines.append(f"  {slot}: {template_id}")

        caller.msg("\n".join(lines))
