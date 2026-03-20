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


def menunode_create_name(caller, raw_string, **kwargs):
    """Prompt for character name, or process input if name was entered."""
    session = kwargs.get("session")

    # Check if we're receiving input (raw_string is the user's typed name)
    name = raw_string.strip() if raw_string else ""

    # If this looks like actual name input (not a menu selection number),
    # process it and advance
    if kwargs.get("got_input") and name:
        if not hasattr(caller.ndb, '_create_data') or caller.ndb._create_data is None:
            caller.ndb._create_data = {}
        caller.ndb._create_data['name'] = name
        return menunode_create_skill(caller, raw_string, session=session)

    if kwargs.get("got_input") and not name:
        caller.msg("|rName cannot be empty.|n", session=session)

    text = "\n|wCharacter Creation|n\n\nEnter your character's name:"
    options = {
        "key": "_default",
        "goto": ("menunode_create_name", {"session": session, "got_input": True}),
    }
    return text, options


SKILL_CHOICES = ["Explore", "Hide", "Smalltalk"]
SKILL_INITIAL = (5.0, 0.0)


def menunode_create_skill(caller, raw_string, **kwargs):
    """Choose a starting skill."""
    session = kwargs.get("session")

    text = "\n|wChoose a Starting Skill|n\n\n"
    text += "  Your chosen skill begins at |c5.00|n.\n\n"

    options = []
    for skill_name in SKILL_CHOICES:
        options.append({
            "desc": f"|w{skill_name}|n",
            "goto": ("menunode_create_skill_set", {"session": session, "skill": skill_name}),
        })
    options.append({
        "desc": "|wBack|n - Re-enter name",
        "goto": ("menunode_create_name", {"session": session}),
    })
    return text, options


def menunode_create_skill_set(caller, raw_string, **kwargs):
    """Store chosen skill and advance to confirm."""
    session = kwargs.get("session")
    skill = kwargs.get("skill", "")

    if not hasattr(caller.ndb, '_create_data') or caller.ndb._create_data is None:
        caller.ndb._create_data = {}
    caller.ndb._create_data['skills'] = {skill: list(SKILL_INITIAL)}

    return menunode_create_confirm(caller, raw_string, session=session)


def menunode_create_confirm(caller, raw_string, **kwargs):
    """Show summary and confirm character creation."""
    session = kwargs.get("session")
    create_data = caller.ndb._create_data or {}
    name = create_data.get('name', '???')

    skills = create_data.get('skills', {})
    skill_name = next(iter(skills), '???')

    text = f"\n|wConfirm Character Creation|n\n\n"
    text += f"  Name:  |c{name}|n\n"
    text += f"  Skill: |c{skill_name}|n |w5.00|n\n\n"
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

        # Find a starting room (first Minare-synced room available)
        from typeclasses.rooms import Room
        start_room = None
        for room in Room.objects.all():
            if room.db.minare_eo_id:
                start_room = room
                break

        character = create_object(
            PlayerCharacter,
            key=character_name,
            location=start_room,
            home=start_room,
        )
        character.link_domain_entity(character_minare_id, "EvenniaCharacter")
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
