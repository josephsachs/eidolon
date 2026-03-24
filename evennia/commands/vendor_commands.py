"""
Vendor Commands

Commands for buying and selling items from vendor NPCs.

Usage:
  buy                       - show menu of nearest vendor
  buy <item> from <vendor>  - buy an item
  sell                      - show what nearest vendor buys
  sell <item> to <vendor>   - sell an item
"""

from commands.command import Command


def _get_client():
    from server.conf.minare_client import get_minare_client
    return get_minare_client()


def _resolve_vendor(caller, vendor_name):
    """Find a vendor by name in the caller's room, return (npc, vendor_minare_id) or None."""
    from typeclasses.characters import NonplayerCharacter
    if not caller.location:
        return None, None
    target = caller.search(vendor_name, location=caller.location, typeclass=NonplayerCharacter, quiet=True)
    if not target:
        return None, None
    npc = target[0] if isinstance(target, list) else target
    vendor_id = npc.db.minare_domain_id or ""
    if not vendor_id:
        return None, None
    return npc, vendor_id


def _find_any_vendor(caller):
    """Find any NPC in the room that has a minare_domain_id (fallback for no-arg usage)."""
    from typeclasses.characters import NonplayerCharacter
    if not caller.location:
        return None, None
    for obj in caller.location.contents:
        if isinstance(obj, NonplayerCharacter) and obj.db.minare_domain_id:
            return obj, obj.db.minare_domain_id
    return None, None


class CmdBuy(Command):
    """
    Buy an item from a vendor.

    Usage:
      buy
      buy <item> from <vendor>

    With no arguments, shows the nearest vendor's buy menu.
    """
    key = "buy"
    locks = "cmd:all()"
    help_category = "Commerce"

    def func(self):
        caller = self.caller
        char_id = caller.db.minare_domain_id or ""
        if not char_id:
            caller.msg("No character data available.")
            return

        args = self.args.strip()

        if not args:
            # Show menu from nearest vendor
            vendor, vendor_id = _find_any_vendor(caller)
            if not vendor:
                caller.msg("There's no one here to buy from.")
                return
            self._show_menu(caller, vendor, vendor_id)
            return

        # Parse "item from vendor"
        if " from " in args:
            item_name, vendor_name = args.rsplit(" from ", 1)
            item_name = item_name.strip()
            vendor_name = vendor_name.strip()
        else:
            # Try: just an item name, find nearest vendor
            item_name = args
            vendor_name = None

        if vendor_name:
            vendor, vendor_id = _resolve_vendor(caller, vendor_name)
        else:
            vendor, vendor_id = _find_any_vendor(caller)

        if not vendor:
            caller.msg("There's no one here to buy from." if not vendor_name
                       else f"Can't find '{vendor_name}' here.")
            return

        def on_buy(response):
            if not response.get('success'):
                caller.msg(f"|r{response.get('reason', 'Purchase failed.')}|n")

        _get_client().send_with_callback(
            {
                'type': 'vendor_buy',
                'vendor_id': vendor_id,
                'character_id': char_id,
                'item_name': item_name,
            },
            on_buy,
        )

    def _show_menu(self, caller, vendor, vendor_id):
        def on_menu(response):
            if not response.get('success'):
                caller.msg(response.get('reason', 'Error'))
                return
            items = response.get('items', [])
            if not items:
                caller.msg(f"{vendor.key} has nothing for sale.")
                return
            lines = [f"|w{vendor.key}'s wares:|n"]
            for item in items:
                lines.append(f"  {item['name']} — {item['price']} {item['currency']}")
            caller.msg("\n".join(lines))

        _get_client().send_with_callback(
            {
                'type': 'vendor_menu',
                'vendor_id': vendor_id,
                'menu_type': 'buy',
            },
            on_menu,
        )


class CmdSell(Command):
    """
    Sell an item to a vendor.

    Usage:
      sell
      sell <item> to <vendor>

    With no arguments, shows what the nearest vendor will buy.
    """
    key = "sell"
    locks = "cmd:all()"
    help_category = "Commerce"

    def func(self):
        caller = self.caller
        char_id = caller.db.minare_domain_id or ""
        if not char_id:
            caller.msg("No character data available.")
            return

        args = self.args.strip()

        if not args:
            vendor, vendor_id = _find_any_vendor(caller)
            if not vendor:
                caller.msg("There's no one here to sell to.")
                return
            self._show_menu(caller, vendor, vendor_id)
            return

        # Parse "item to vendor"
        if " to " in args:
            item_name, vendor_name = args.rsplit(" to ", 1)
            item_name = item_name.strip()
            vendor_name = vendor_name.strip()
        else:
            item_name = args
            vendor_name = None

        if vendor_name:
            vendor, vendor_id = _resolve_vendor(caller, vendor_name)
        else:
            vendor, vendor_id = _find_any_vendor(caller)

        if not vendor:
            caller.msg("There's no one here to sell to." if not vendor_name
                       else f"Can't find '{vendor_name}' here.")
            return

        # Try physical item first, then check resources
        item = caller.search(item_name, location=caller, quiet=True)
        if item:
            item = item[0] if isinstance(item, list) else item
            template_id = getattr(item.db, 'template_id', None)
            if not template_id:
                caller.msg("You can't sell that.")
                return
        else:
            # Check resources by name match
            template_id = None
            sim_state = caller.db.sim_state or {}
            resources = sim_state.get('resources', {})
            query = item_name.lower()
            for tid, count in resources.items():
                name = tid.replace("-", " ").lower()
                if query == tid or query == name or name.startswith(query):
                    template_id = tid
                    break
            if not template_id:
                caller.msg(f"You don't have '{item_name}' to sell.")
                return

        def on_sell(response):
            if not response.get('success'):
                caller.msg(f"|r{response.get('reason', 'Sale failed.')}|n")

        _get_client().send_with_callback(
            {
                'type': 'vendor_sell',
                'vendor_id': vendor_id,
                'character_id': char_id,
                'template_id': template_id,
            },
            on_sell,
        )

    def _show_menu(self, caller, vendor, vendor_id):
        def on_menu(response):
            if not response.get('success'):
                caller.msg(response.get('reason', 'Error'))
                return
            items = response.get('items', [])
            if not items:
                caller.msg(f"{vendor.key} isn't buying anything.")
                return
            lines = [f"|w{vendor.key} is buying:|n"]
            for item in items:
                lines.append(f"  {item['name']} — {item['price']} {item['currency']}")
            caller.msg("\n".join(lines))

        _get_client().send_with_callback(
            {
                'type': 'vendor_menu',
                'vendor_id': vendor_id,
                'menu_type': 'sell',
            },
            on_menu,
        )
