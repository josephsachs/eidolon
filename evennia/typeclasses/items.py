"""
Items

Items are Objects with a template_id linking them to a Minare item template.
They can be picked up, dropped, equipped, and traded.

Stackable items share a single Object with a stack_count attribute.
"""

from evennia.objects.objects import DefaultObject

from .objects import ObjectParent


class Item(ObjectParent, DefaultObject):
    """
    An item in the game world. Linked to a Minare item template
    via db.template_id for stats lookup.

    Stackable items use db.stack_count (default 1).
    """

    def at_object_creation(self):
        super().at_object_creation()
        self.db.template_id = ""
        self.db.stack_count = 1

    def get_display_name(self, looker=None, **kwargs):
        count = self.db.stack_count or 1
        if count > 1:
            return f"{self.key} x{count}"
        return self.key

    def at_post_move(self, source_location, **kwargs):
        """After moving, merge into any existing stack at the new location.

        Skip merge when picked up by a character — Evennia's CmdGet still
        holds a reference to the moved object and will crash if we delete
        it mid-command (get_numbered_name on a deleted object has no DB id).
        Merging still happens on drop/room placement.
        """
        super().at_post_move(source_location, **kwargs)
        if not self.location or not self.db.template_id:
            return
        from typeclasses.characters import Character
        if isinstance(self.location, Character):
            return
        for obj in self.location.contents:
            if obj is self:
                continue
            if isinstance(obj, Item) and getattr(obj.db, 'template_id', '') == self.db.template_id:
                # Merge into existing stack
                obj.add_to_stack(self.db.stack_count or 1)
                self.delete()
                return

    def add_to_stack(self, amount=1):
        """Increase stack count."""
        self.db.stack_count = (self.db.stack_count or 1) + amount

    def remove_from_stack(self, amount=1):
        """Decrease stack count. Returns True if item should be deleted (empty stack)."""
        current = self.db.stack_count or 1
        if amount >= current:
            return True
        self.db.stack_count = current - amount
        return False


def find_item_stack(location, template_id):
    """Find an existing stack of the given template in a location."""
    for obj in location.contents:
        if isinstance(obj, Item) and getattr(obj.db, 'template_id', '') == template_id:
            return obj
    return None
