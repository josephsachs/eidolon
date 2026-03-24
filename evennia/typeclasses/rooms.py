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

    def at_object_receive(self, obj, source_location, move_type="move", **kwargs):
        super().at_object_receive(obj, source_location, move_type=move_type, **kwargs)
        self._notify_presence(obj, "arrived")

    def at_object_leave(self, obj, target_location, move_type="move", **kwargs):
        super().at_object_leave(obj, target_location, move_type=move_type, **kwargs)
        self._notify_presence(obj, "departed")

    def _notify_presence(self, obj, event):
        """Tell Minare when a character enters or leaves this room."""
        from typeclasses.characters import PlayerCharacter, NonplayerCharacter
        if not isinstance(obj, (PlayerCharacter, NonplayerCharacter)):
            return
        domain_id = getattr(obj.db, 'minare_domain_id', None)
        if not domain_id:
            return
        try:
            from server.conf.minare_client import get_minare_client
            client = get_minare_client()

            occupants = []
            for other in self.contents:
                if other == obj:
                    continue
                if not isinstance(other, (PlayerCharacter, NonplayerCharacter)):
                    continue
                other_id = getattr(other.db, 'minare_domain_id', None)
                if other_id:
                    occupants.append({
                        "id": other_id,
                        "is_npc": isinstance(other, NonplayerCharacter),
                    })

            client.send_message({
                "type": "presence",
                "event": event,
                "character_id": domain_id,
                "is_npc": isinstance(obj, NonplayerCharacter),
                "room_id": self.db.minare_domain_id or "",
                "occupants": occupants,
            })
        except Exception:
            pass
