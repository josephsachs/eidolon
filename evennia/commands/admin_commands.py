"""
Admin/Builder Commands

Commands for privileged users to create and manage Minare-backed entities.
"""

from commands.command import Command


def _get_client():
    """Get the Minare client singleton."""
    from server.conf.minare_client import get_minare_client
    return get_minare_client()


class CmdMCreate(Command):
    """
    Create a Minare-backed item in the current room.

    Usage:
      @mcreate <name>[=<description>]

    Examples:
      @mcreate sword
      @mcreate bronze sword=A sturdy blade, green with age.
    """
    key = "@mcreate"
    locks = "cmd:perm(Builder)"
    help_category = "Building"

    def func(self):
        if not self.args:
            self.caller.msg("Usage: @mcreate <name>[=<description>]")
            return

        if "=" in self.args:
            name, description = self.args.split("=", 1)
            name = name.strip()
            description = description.strip()
        else:
            name = self.args.strip()
            description = ""

        room_id = ""
        if self.caller.location and hasattr(self.caller.location, 'db'):
            room_id = getattr(self.caller.location.db, 'minare_id', None) or ""

        if not room_id:
            self.caller.msg("You must be in a Minare-synced room.")
            return

        # Register pending create so DownSocket handler knows where to place it
        from server.conf.minare_client import register_pending_create
        register_pending_create(name, self.caller.location)

        _get_client().send_message({
            "type": "command_create_item",
            "name": name,
            "description": description,
            "room_id": room_id,
        })
        self.caller.msg(f"Creating '{name}'...")
