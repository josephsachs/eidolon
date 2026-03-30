"""
Exits

Exits are connectors between Rooms. An exit always has a destination property
set and has a single command defined on itself with the same name as its key,
for allowing Characters to traverse the exit to its destination.

"""

from evennia.objects.objects import DefaultExit

from .objects import ObjectParent


class Exit(ObjectParent, DefaultExit):
    """
    Exits are connectors between rooms. Exits are normal Objects except
    they defines the `destination` property and overrides some hooks
    and methods to represent the exits.

    See mygame/typeclasses/objects.py for a list of
    properties and methods available on all Objects child classes like this.

    Attributes:
        is_stile (bool): If True, NPCs will not use this exit when wandering
            randomly. Set at scenario design time via ``exit.db.is_stile = True``.
    """

    def at_object_creation(self):
        super().at_object_creation()
        self.db.is_stile = False
