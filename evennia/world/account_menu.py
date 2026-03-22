"""
Account Menu (EvMenu)

Presented to players after login. Provides Create/Play/Quit options.
Designed as a chain of nodes — adding more character creation steps
is just inserting nodes between _name and _confirm.
"""

from evennia import create_object
from evennia.utils import logger


def menunode_main(caller, raw_string, **kwargs):
    """Main menu: show options based on whether a character exists."""
    character_ids = caller.db.minare_character_ids or []
    session = kwargs.get("session") or caller.sessions.get()[0] if caller.sessions.get() else None

    text = "\n|c======================|n\n"
    text += "|w      EIDOLON|n\n"
    text += "|c======================|n\n\n"

    options = []

    if character_ids:
        text += "  You have an existing character.\n\n"
        options.append({
            "desc": "|wPlay|n - Enter the game",
            "goto": ("menunode_play", {"session": session}),
        })
        options.append({
            "desc": "|wCreate|n - Create a new character (overwrites existing)",
            "goto": ("menunode_create_name", {"session": session}),
        })
    else:
        text += "  No character found. Create one to begin.\n\n"
        options.append({
            "desc": "|wCreate|n - Create a new character",
            "goto": ("menunode_create_name", {"session": session}),
        })

    options.append({
        "desc": "|wQuit|n - Disconnect",
        "goto": "menunode_quit",
    })

    return text, options


def _init_create_data(caller):
    if not hasattr(caller.ndb, '_create_data') or caller.ndb._create_data is None:
        caller.ndb._create_data = {}
    return caller.ndb._create_data


# --- Attribute configuration ---
ATTR_BOOST = 20
ATTR_DUMP_PENALTY = 20
ATTR_DUMP_BONUS = 10

ALL_ATTRIBUTES = [
    "strength", "agility", "toughness",
    "intellect", "imagination", "discipline",
    "charisma", "empathy", "wits",
]

PHYSICAL_ATTRS = ["strength", "agility", "toughness"]
MENTAL_ATTRS = ["intellect", "imagination", "discipline"]
SOCIAL_ATTRS = ["charisma", "empathy", "wits"]

SKILL_CHOICES = ["Explore", "Hide", "Smalltalk", "Hand-to-Hand", "Blades", "Firearms", "Escape"]
SKILL_INITIAL = (5.0, 0.0)
NUM_SKILLS = 3


def menunode_create_name(caller, raw_string, **kwargs):
    """Prompt for character name."""
    session = kwargs.get("session")
    name = raw_string.strip() if raw_string else ""

    if kwargs.get("got_input") and name:
        data = _init_create_data(caller)
        data['name'] = name
        data['attr_boosts'] = []
        data['attr_dump'] = None
        data['attr_dump_boosts'] = []
        data['skills'] = {}
        return menunode_attr_physical(caller, raw_string, session=session)

    if kwargs.get("got_input") and not name:
        caller.msg("|rName cannot be empty.|n", session=session)

    text = "\n|wCharacter Creation|n\n\nEnter your character's name:"
    options = {
        "key": "_default",
        "goto": ("menunode_create_name", {"session": session, "got_input": True}),
    }
    return text, options


def menunode_attr_physical(caller, raw_string, **kwargs):
    """Choose physical attribute to boost."""
    session = kwargs.get("session")
    text = "\n|wPhysical Attribute|n\n\n"
    text += f"  Choose one to boost by |c+{ATTR_BOOST}|n:\n\n"

    options = []
    for attr in PHYSICAL_ATTRS:
        options.append({
            "desc": f"|w{attr.capitalize()}|n",
            "goto": ("menunode_attr_set", {"session": session, "attr": attr, "next": "menunode_attr_mental"}),
        })
    options.append({
        "desc": "|wBack|n - Re-enter name",
        "goto": ("menunode_create_name", {"session": session}),
    })
    return text, options


def menunode_attr_mental(caller, raw_string, **kwargs):
    """Choose mental attribute to boost."""
    session = kwargs.get("session")
    text = "\n|wMental Attribute|n\n\n"
    text += f"  Choose one to boost by |c+{ATTR_BOOST}|n:\n\n"

    options = []
    for attr in MENTAL_ATTRS:
        options.append({
            "desc": f"|w{attr.capitalize()}|n",
            "goto": ("menunode_attr_set", {"session": session, "attr": attr, "next": "menunode_attr_social"}),
        })
    options.append({
        "desc": "|wBack|n",
        "goto": ("menunode_attr_physical", {"session": session}),
    })
    return text, options


def menunode_attr_social(caller, raw_string, **kwargs):
    """Choose social attribute to boost."""
    session = kwargs.get("session")
    text = "\n|wSocial Attribute|n\n\n"
    text += f"  Choose one to boost by |c+{ATTR_BOOST}|n:\n\n"

    options = []
    for attr in SOCIAL_ATTRS:
        options.append({
            "desc": f"|w{attr.capitalize()}|n",
            "goto": ("menunode_attr_set", {"session": session, "attr": attr, "next": "menunode_dump_ask"}),
        })
    options.append({
        "desc": "|wBack|n",
        "goto": ("menunode_attr_mental", {"session": session}),
    })
    return text, options


def menunode_attr_set(caller, raw_string, **kwargs):
    """Store an attribute boost and advance."""
    session = kwargs.get("session")
    attr = kwargs.get("attr", "")
    next_node = kwargs.get("next", "")

    data = _init_create_data(caller)
    data['attr_boosts'].append(attr)

    if next_node == "menunode_attr_mental":
        return menunode_attr_mental(caller, raw_string, session=session)
    elif next_node == "menunode_attr_social":
        return menunode_attr_social(caller, raw_string, session=session)
    else:
        return menunode_dump_ask(caller, raw_string, session=session)


def menunode_dump_ask(caller, raw_string, **kwargs):
    """Ask if player wants a dump stat."""
    session = kwargs.get("session")
    data = _init_create_data(caller)

    boosts = data.get('attr_boosts', [])
    text = "\n|wDump Stat|n\n\n"
    text += f"  Boosted: |c{', '.join(a.capitalize() for a in boosts)}|n\n\n"
    text += f"  Do you want a dump stat? (|r-{ATTR_DUMP_PENALTY}|n to one attribute,\n"
    text += f"  |c+{ATTR_DUMP_BONUS}|n to two others of your choice)\n\n"

    options = [
        {
            "desc": "|wYes|n - Choose a dump stat",
            "goto": ("menunode_dump_choose", {"session": session}),
        },
        {
            "desc": "|wNo|n - Keep attributes balanced",
            "goto": ("menunode_skill_choose", {"session": session, "skill_num": 1}),
        },
        {
            "desc": "|wBack|n",
            "goto": ("menunode_attr_social", {"session": session}),
        },
    ]
    return text, options


def menunode_dump_choose(caller, raw_string, **kwargs):
    """Choose which attribute to dump."""
    session = kwargs.get("session")
    data = _init_create_data(caller)
    boosts = data.get('attr_boosts', [])

    text = "\n|wChoose Dump Stat|n\n\n"
    text += f"  This attribute will be reduced by |r{ATTR_DUMP_PENALTY}|n.\n\n"

    options = []
    for attr in ALL_ATTRIBUTES:
        options.append({
            "desc": f"|w{attr.capitalize()}|n" + (" |c(boosted)|n" if attr in boosts else ""),
            "goto": ("menunode_dump_set", {"session": session, "dump": attr}),
        })
    options.append({
        "desc": "|wBack|n",
        "goto": ("menunode_dump_ask", {"session": session}),
    })
    return text, options


def menunode_dump_set(caller, raw_string, **kwargs):
    """Store dump stat and advance to bonus picks."""
    session = kwargs.get("session")
    data = _init_create_data(caller)
    data['attr_dump'] = kwargs.get("dump", "")
    data['attr_dump_boosts'] = []
    return menunode_dump_bonus(caller, raw_string, session=session, pick_num=1)


def menunode_dump_bonus(caller, raw_string, **kwargs):
    """Choose attributes to boost with dump stat bonus (2 picks)."""
    session = kwargs.get("session")
    pick_num = kwargs.get("pick_num", 1)
    data = _init_create_data(caller)

    dump = data.get('attr_dump', '')
    already_picked = data.get('attr_dump_boosts', [])

    text = f"\n|wDump Stat Bonus ({pick_num}/2)|n\n\n"
    text += f"  Dumped: |r{dump.capitalize()}|n\n"
    text += f"  Choose an attribute to boost by |c+{ATTR_DUMP_BONUS}|n:\n\n"

    options = []
    for attr in ALL_ATTRIBUTES:
        if attr == dump or attr in already_picked:
            continue
        options.append({
            "desc": f"|w{attr.capitalize()}|n",
            "goto": ("menunode_dump_bonus_set", {"session": session, "attr": attr, "pick_num": pick_num}),
        })
    return text, options


def menunode_dump_bonus_set(caller, raw_string, **kwargs):
    """Store a dump bonus pick."""
    session = kwargs.get("session")
    attr = kwargs.get("attr", "")
    pick_num = kwargs.get("pick_num", 1)
    data = _init_create_data(caller)

    data['attr_dump_boosts'].append(attr)

    if pick_num < 2:
        return menunode_dump_bonus(caller, raw_string, session=session, pick_num=pick_num + 1)
    else:
        return menunode_skill_choose(caller, raw_string, session=session, skill_num=1)


def menunode_skill_choose(caller, raw_string, **kwargs):
    """Choose starting skills (3 picks, each at 5.00)."""
    session = kwargs.get("session")
    skill_num = kwargs.get("skill_num", 1)
    data = _init_create_data(caller)

    chosen = list(data.get('skills', {}).keys())

    text = f"\n|wChoose Starting Skill ({skill_num}/{NUM_SKILLS})|n\n\n"
    text += "  Each chosen skill begins at |c5.00|n.\n"
    if chosen:
        text += f"  Already chosen: |c{', '.join(chosen)}|n\n"
    text += "\n"

    options = []
    for skill_name in SKILL_CHOICES:
        if skill_name in chosen:
            continue
        options.append({
            "desc": f"|w{skill_name}|n",
            "goto": ("menunode_skill_set", {"session": session, "skill": skill_name, "skill_num": skill_num}),
        })
    if skill_num > 1:
        options.append({
            "desc": "|wBack|n - Re-pick previous skill",
            "goto": ("menunode_skill_undo", {"session": session, "skill_num": skill_num}),
        })
    return text, options


def menunode_skill_set(caller, raw_string, **kwargs):
    """Store a skill pick."""
    session = kwargs.get("session")
    skill = kwargs.get("skill", "")
    skill_num = kwargs.get("skill_num", 1)
    data = _init_create_data(caller)

    data['skills'][skill] = list(SKILL_INITIAL)

    if skill_num < NUM_SKILLS:
        return menunode_skill_choose(caller, raw_string, session=session, skill_num=skill_num + 1)
    else:
        return menunode_create_equipment(caller, raw_string, session=session)


def menunode_skill_undo(caller, raw_string, **kwargs):
    """Remove the last skill pick and re-pick."""
    session = kwargs.get("session")
    skill_num = kwargs.get("skill_num", 1)
    data = _init_create_data(caller)

    skills = data.get('skills', {})
    if skills:
        last_key = list(skills.keys())[-1]
        del skills[last_key]

    return menunode_skill_choose(caller, raw_string, session=session, skill_num=skill_num - 1)


EQUIPMENT_CHOICES = {
    "Rusty knife": "rusty-knife",
    "Hard hat": "hard-hat",
    "Leather vest": "leather-vest",
    "Work boots": "work-boots",
    "Nothing": "",
}


def menunode_create_equipment(caller, raw_string, **kwargs):
    """Choose starting equipment."""
    session = kwargs.get("session")

    text = "\n|wChoose Starting Equipment|n\n\n"
    text += "  Pick one item to start with.\n\n"

    options = []
    for name, template_id in EQUIPMENT_CHOICES.items():
        options.append({
            "desc": f"|w{name}|n",
            "goto": ("menunode_create_equipment_set", {"session": session, "equipment": name, "template_id": template_id}),
        })
    options.append({
        "desc": "|wBack|n - Re-choose last skill",
        "goto": ("menunode_skill_undo", {"session": session, "skill_num": NUM_SKILLS}),
    })
    return text, options


def menunode_create_equipment_set(caller, raw_string, **kwargs):
    """Store chosen equipment and advance to confirm."""
    session = kwargs.get("session")
    equipment_name = kwargs.get("equipment", "")
    template_id = kwargs.get("template_id", "")

    if not hasattr(caller.ndb, '_create_data') or caller.ndb._create_data is None:
        caller.ndb._create_data = {}

    if template_id:
        caller.ndb._create_data['starting_equipment'] = {
            'name': equipment_name,
            'template_id': template_id,
        }
    else:
        caller.ndb._create_data['starting_equipment'] = None

    return menunode_create_confirm(caller, raw_string, session=session)


def menunode_create_confirm(caller, raw_string, **kwargs):
    """Show summary and confirm character creation."""
    session = kwargs.get("session")
    create_data = caller.ndb._create_data or {}
    name = create_data.get('name', '???')

    # Build attribute summary
    boosts = create_data.get('attr_boosts', [])
    dump = create_data.get('attr_dump')
    dump_boosts = create_data.get('attr_dump_boosts', [])

    base = 50
    attr_values = {a: base for a in ALL_ATTRIBUTES}
    for a in boosts:
        attr_values[a] = attr_values.get(a, base) + ATTR_BOOST
    if dump:
        attr_values[dump] = attr_values.get(dump, base) - ATTR_DUMP_PENALTY
    for a in dump_boosts:
        attr_values[a] = attr_values.get(a, base) + ATTR_DUMP_BONUS

    skills = create_data.get('skills', {})
    equipment = create_data.get('starting_equipment')
    equipment_name = equipment.get('name', 'Nothing') if equipment else 'Nothing'

    text = f"\n|wConfirm Character Creation|n\n\n"
    text += f"  Name: |c{name}|n\n\n"
    text += "  |wAttributes:|n\n"
    for attr in ALL_ATTRIBUTES:
        val = attr_values[attr]
        tag = ""
        if attr in boosts:
            tag = " |c(boosted)|n"
        if attr == dump:
            tag = " |r(dump)|n"
        if attr in dump_boosts:
            tag += " |y(+bonus)|n"
        text += f"    {attr.capitalize():14s} |w{val}|n{tag}\n"
    text += f"\n  |wSkills:|n\n"
    for skill_name in skills:
        text += f"    {skill_name:14s} |w5.00|n\n"
    text += f"\n  |wEquipment:|n |c{equipment_name}|n\n\n"
    text += "Create this character?"

    options = [
        {
            "desc": "|wConfirm|n",
            "goto": ("menunode_do_create", {"session": session}),
        },
        {
            "desc": "|wStart over|n",
            "goto": ("menunode_create_name", {"session": session}),
        },
        {
            "desc": "|wCancel|n",
            "goto": ("menunode_main", {"session": session}),
        },
    ]
    return text, options


def menunode_do_create(caller, raw_string, **kwargs):
    """Send character creation request to Minare."""
    session = kwargs.get("session")
    create_data = caller.ndb._create_data or {}

    from server.conf.minare_client import get_minare_client
    client = get_minare_client()

    # Store session reference for the callback
    caller.ndb._pending_session = session

    def on_character_created(response):
        _session = caller.ndb._pending_session
        if response.get('status') != 'success':
            caller.msg(
                f"|rCharacter creation failed: {response.get('error', 'unknown')}|n",
                session=_session,
            )
            # Return to main menu
            from evennia.utils.evmenu import EvMenu
            EvMenu(
                caller,
                "world.account_menu",
                startnode="menunode_main",
                session=_session,
                persistent=False,
            )
            return

        character_data = response.get('character', {})
        character_minare_id = character_data.get('_id', '')
        character_name = character_data.get('evenniaName', 'Unknown')

        # Update account's character list and name
        caller.db.minare_character_ids = [character_minare_id]
        caller.db.minare_character_name = character_name

        caller.msg(
            f"\n|gCharacter '{character_name}' created!|n\n",
            session=_session,
        )

        # Now puppet the character
        _puppet_character(caller, character_minare_id, character_name, _session)

    caller.msg("\n|yCreating character...|n", session=session)

    client.send_with_callback(
        {
            'type': 'create_character',
            'account_id': caller.db.minare_account_id,
            'character_data': create_data,
        },
        on_character_created,
    )

    # Return a waiting node — the callback will handle advancing
    text = ""
    return text, None


def menunode_play(caller, raw_string, **kwargs):
    """Puppet the existing character."""
    session = kwargs.get("session") or (caller.sessions.get()[0] if caller.sessions.get() else None)
    character_ids = caller.db.minare_character_ids or []

    if not character_ids:
        caller.msg("|rNo character found. Please create one first.|n", session=session)
        return "menunode_main", {"session": session}

    character_minare_id = character_ids[0]
    # Use stored character name if available
    character_name = caller.db.minare_character_name or None
    _puppet_character(caller, character_minare_id, character_name, session)

    return "", None


def menunode_quit(caller, raw_string, **kwargs):
    """Disconnect the session."""
    caller.msg("|wGoodbye!|n")
    sessions = caller.sessions.get()
    if sessions:
        sessions[0].sessionhandler.disconnect(sessions[0])
    return "", None


def _puppet_character(caller, character_minare_id, character_name, session):
    """
    Find or create the Evennia Character for the given Minare ID,
    then puppet it.
    """
    from typeclasses.characters import PlayerCharacter
    from evennia import create_object

    # Search for existing Evennia character with this domain entity ID
    character = None
    for char in PlayerCharacter.objects.all():
        if char.db.minare_domain_id == character_minare_id:
            character = char
            break

    if not character:
        # Create the Evennia Character typeclass
        if not character_name:
            character_name = "Unknown"

        # Find a starting room — first game world room (has domain link, not a system room)
        from typeclasses.rooms import Room
        start_room = None
        for room in Room.objects.all():
            if room.db.minare_domain_id and not room.db.account_vault:
                start_room = room
                break

        character = create_object(
            PlayerCharacter,
            key=character_name,
            location=start_room,
            home=start_room,
        )
        character.link_domain_entity(character_minare_id, "EvenniaCharacter")

        # Create starting equipment item if specified
        create_data = getattr(caller.ndb, '_create_data', None) or {}
        starting_eq = create_data.get('starting_equipment')
        if starting_eq and starting_eq.get('template_id'):
            from typeclasses.items import Item
            item = create_object(
                Item,
                key=starting_eq['name'],
                location=character,
            )
            item.db.template_id = starting_eq['template_id']
        logger.log_info(
            f"Account menu: Created Evennia Character '{character_name}' "
            f"in room '{start_room}' for domain_id={character_minare_id}"
        )

    # Ensure puppet permissions: set lock and register as playable
    character.locks.add(f"puppet:id({caller.id}) or perm(Developer)")
    caller.db._playable_characters = caller.db._playable_characters or []
    if character not in caller.db._playable_characters:
        caller.db._playable_characters.append(character)
    caller.db._last_puppet_key = character.dbref

    # Puppet the character
    try:
        caller.puppet_object(session, character)
    except Exception as e:
        logger.log_err(f"Account menu: Puppet failed for {character_minare_id}: {e}")
        caller.msg(f"|rFailed to enter game: {e}|n", session=session)
        # Return to menu on failure
        from evennia.utils.evmenu import EvMenu
        EvMenu(caller, "world.account_menu", startnode="menunode_main",
               session=session, persistent=False)
