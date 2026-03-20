"""
Room

Rooms are simple containers that has no location of their own.

"""

from evennia.objects.objects import DefaultRoom

from .objects import ObjectParent


class Room(ObjectParent, DefaultRoom):
    """
    Rooms are like any Object, except their location is None
    (which is default). They also use basetype_setup() to
    add locks so they cannot be puppeted or picked up.
    (to change that, use at_object_creation instead)

    See mygame/typeclasses/objects.py for a list of
    properties and methods available on all Objects.
    """

    def at_object_creation(self):
        super().at_object_creation()
        self.db.sim_state = {}

    @property
    def concealment(self):
        """Room concealment from Minare sim state."""
        sim_state = self.db.sim_state or {}
        return sim_state.get("concealment", 0)

    def get_display_desc(self, looker, **kwargs):
        """Use sim-synced description if available, otherwise fall back to default."""
        sim_state = self.db.sim_state or {}
        sim_desc = sim_state.get("description")
        if sim_desc:
            return sim_desc
        return super().get_display_desc(looker, **kwargs) or ""
