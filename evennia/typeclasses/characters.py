"""
Characters

Characters are (by default) Objects setup to be puppeted by Accounts.
They are what you "see" in game. The Character class in this module
is setup to be the "default" character type created by the default
creation commands.

"""

from evennia.objects.objects import DefaultCharacter
from evennia.utils import logger

from .objects import ObjectParent


class Character(ObjectParent, DefaultCharacter):
    """
    Base Character typeclass. All characters in Eidolon inherit from this.
    """

    def at_pre_move(self, destination, move_type="move", **kwargs):
        """Block movement when in combat, dead, or downed."""
        if self.db.in_combat:
            self.msg("|rYou can't leave — you're in combat!|n")
            return False
        if self.db.is_dead:
            self.msg("|xYou're dead. You aren't going anywhere.|n")
            return False
        if self.db.is_downed:
            self.msg("|rYou're too injured to move.|n")
            return False
        return super().at_pre_move(destination, move_type=move_type, **kwargs)


class PlayerCharacter(Character):
    """
    A human-controlled character. Puppeted by an Account, subject to the
    5 passthrough sagas (get, drop, give, move, home). Its state is an
    eventually-consistent projection of what Minare pushes via the downsocket.
    """

    def at_object_creation(self):
        """Attach the PlayerCharacter command set."""
        super().at_object_creation()
        from commands.default_cmdsets import PlayerCharacterCmdSet
        self.cmdset.add(PlayerCharacterCmdSet, persistent=True)

    def hide(self, degree):
        """Apply hiding: restrict visibility and store hidden strength."""
        self.db.hidden_mod = degree
        self.locks.add("view:perm(Builder)")

    def unhide(self):
        """Remove hiding: restore visibility."""
        if self.db.hidden_mod is not None:
            del self.db.hidden_mod
        self.locks.add("view:all()")

    def at_object_leave(self, moved_obj, target_location, move_type="move", **kwargs):
        """Auto-unequip items leaving inventory (drop, give, etc.)."""
        super().at_object_leave(moved_obj, target_location, move_type=move_type, **kwargs)
        template_id = getattr(moved_obj.db, 'template_id', None)
        if not template_id:
            return
        sim_state = self.db.sim_state or {}
        equipment = sim_state.get('equipment', {})
        slot = None
        for s, tid in equipment.items():
            if tid == template_id:
                slot = s
                break
        if not slot:
            return
        try:
            from server.conf.minare_client import get_minare_client
            client = get_minare_client()
            char_id = self.db.minare_domain_id or ""
            if char_id:
                client.send_message({
                    "type": "unequip_item",
                    "character_id": char_id,
                    "slot_or_item": slot,
                })
        except Exception:
            pass

    def at_post_move(self, source_location, move_type="move", **kwargs):
        """Notify Minare when this character changes rooms."""
        super().at_post_move(source_location, move_type=move_type, **kwargs)
        try:
            from server.conf.minare_client import get_minare_client
            client = get_minare_client()
            old_room_id = ""
            if source_location and hasattr(source_location, 'db'):
                old_room_id = source_location.db.minare_domain_id or ""
            new_room_id = ""
            if self.location and hasattr(self.location, 'db'):
                new_room_id = self.location.db.minare_domain_id or ""
            client.send_message({
                "type": "character_moved",
                "character_id": self.db.minare_domain_id or "",
                "old_room_id": old_room_id,
                "new_room_id": new_room_id,
            })
        except Exception:
            pass

    def at_post_unpuppet(self, account, session=None, **kwargs):
        """Inform Minare when player disconnects."""
        super().at_post_unpuppet(account, session=session, **kwargs)
        try:
            from server.conf.minare_client import get_minare_client
            client = get_minare_client()
            room_id = None
            if self.location and hasattr(self.location, 'db'):
                room_id = self.location.db.minare_domain_id
            client.send_message({
                "type": "player_disconnect",
                "character_id": self.db.minare_domain_id or "",
                "room_id": room_id or "",
                "account_id": str(account.id) if account else "",
            })
        except Exception:
            pass


class AgentCharacter(Character):
    """
    A Minare-driven agent inside Evennia. Lives in Limbo, never moves,
    never seen by players. Has builder-level permissions and executes
    structured commands from Minare's downsocket to effect simulation
    outcomes in Evennia.

    Potentially plural at runtime — different agents can own different
    operational domains or be sharded by region.

    Command dispatch:
      - ACTION_HANDLERS: custom methods for commands needing post-execution
        side effects (dig confirmation, object creation, batch iteration).
      - ACTION_ALIASES: action names that differ from the Evennia command name.
      - ACTION_ASSIGNS: commands using "cmd = text" syntax instead of "cmd text".
      - Default: build command string from action + text, execute via cmdset.
        If room_evennia_id is present, execute in that room's context.
    """

    ACTION_ALIASES = {
        'emote': 'pose',
        'dig': '@dig',
    }

    ACTION_ASSIGNS = {
        'describe': 'desc here',
    }

    ACTION_HANDLERS = {
        'create_exit': '_handle_create_exit',
        'create_npc': '_handle_create_npc',
        'create_object': '_handle_create_object',
        'batch': '_handle_batch',
        'unlock_exit': '_handle_unlock_exit',
        'hazard_msg': '_handle_hazard_msg',
        'hazard_damage': '_handle_hazard_damage',
        'flag_dead': '_handle_flag_dead',
        'flag_downed': '_handle_flag_downed',
        'flag_undowned': '_handle_flag_undowned',
        'combat_lock': '_handle_combat_lock',
        'combat_unlock': '_handle_combat_unlock',
        'combat_msg': '_handle_combat_msg',
        'archive_object': '_handle_archive_object',
        'combat_feedback': '_handle_combat_feedback',
        'vendor_buy': '_handle_vendor_buy',
        'vendor_sell': '_handle_vendor_sell',
        'create_item': '_handle_create_item',
        'npc_move': '_handle_npc_move',
        'npc_command': '_handle_npc_command',
    }

    def at_object_creation(self):
        super().at_object_creation()
        from commands.default_cmdsets import AgentCharacterCmdSet
        self.cmdset.add(AgentCharacterCmdSet, persistent=True)
        self.permissions.add("Builder")

    def handle_agent_command(self, command):
        """Dispatch a structured command from Minare."""
        action = command.get('action', '')

        # Custom handler for commands needing side effects
        handler_name = self.ACTION_HANDLERS.get(action)
        if handler_name:
            getattr(self, handler_name)(command)
            return

        # Build command string
        assign_cmd = self.ACTION_ASSIGNS.get(action)
        if assign_cmd:
            cmd_string = f'{assign_cmd} = {command.get("text", "")}'
        else:
            cmd_name = self.ACTION_ALIASES.get(action, action)
            cmd_string = f'{cmd_name} {command.get("text", "")}'

        # Execute with room context if provided, otherwise direct
        room_id = command.get('room_evennia_id')
        if room_id:
            self._execute_in_room(room_id, cmd_string)
        else:
            self.execute_cmd(cmd_string)

    def _execute_in_room(self, room_evennia_id, cmd_string):
        """Temporarily move to a room, execute command, return to previous location."""
        from typeclasses.rooms import Room

        if not room_evennia_id:
            logger.log_err("AgentCharacter._execute_in_room: missing room_evennia_id")
            return

        try:
            room = Room.objects.get(id=int(room_evennia_id))
        except (Room.DoesNotExist, ValueError):
            logger.log_err(f"AgentCharacter: Room not found: {room_evennia_id}")
            return

        old_location = self.location
        self.location = room
        self.execute_cmd(cmd_string)
        self.location = old_location

    def _handle_create_exit(self, command):
        """Create a one-way exit between two existing rooms."""
        from typeclasses.rooms import Room
        from typeclasses.exits import Exit as EvenniaExit
        from evennia import create_object

        exit_name = command.get('exit_name', '')
        from_id = command.get('from_room_evennia_id')
        to_id = command.get('to_room_evennia_id')

        if not exit_name or not from_id or not to_id:
            logger.log_err(
                f"AgentCharacter._handle_create_exit: missing fields "
                f"(exit_name={exit_name}, from={from_id}, to={to_id})"
            )
            return

        try:
            from_room = Room.objects.get(id=int(from_id))
            to_room = Room.objects.get(id=int(to_id))
            new_exit = create_object(
                EvenniaExit, key=exit_name, location=from_room, destination=to_room
            )
            if command.get('locked'):
                new_exit.locks.add("traverse:perm(Admin)")
                block_message = command.get('block_message', 'The path is blocked and impassable.')
                new_exit.db.err_traverse = block_message
            logger.log_info(
                f"AgentCharacter: Created exit '{exit_name}' "
                f"from '{from_room.key}' -> '{to_room.key}'"
                f"{' (locked)' if command.get('locked') else ''}"
            )
        except (Room.DoesNotExist, ValueError) as e:
            logger.log_err(f"AgentCharacter._handle_create_exit: {e}")

    def _handle_create_npc(self, command):
        """Create a NonplayerCharacter in a specific room."""
        from evennia import create_object
        from typeclasses.rooms import Room

        npc_name = command.get('npc_name', '')
        room_evennia_id = command.get('room_evennia_id')

        if not npc_name or not room_evennia_id:
            logger.log_err(
                f"AgentCharacter._handle_create_npc: missing fields "
                f"(npc_name={npc_name}, room={room_evennia_id})"
            )
            return

        try:
            room = Room.objects.get(id=int(room_evennia_id))
            npc = create_object(
                NonplayerCharacter, key=npc_name, location=room
            )
            logger.log_info(
                f"AgentCharacter: Created NPC '{npc_name}' "
                f"in '{room.key}' (id={npc.id})"
            )
        except (Room.DoesNotExist, ValueError) as e:
            logger.log_err(f"AgentCharacter._handle_create_npc: {e}")

    def _handle_unlock_exit(self, command):
        """Unlock a previously locked exit."""
        from typeclasses.exits import Exit as EvenniaExit

        exit_evennia_id = command.get('exit_evennia_id')
        if not exit_evennia_id:
            logger.log_err("_handle_unlock_exit: missing exit_evennia_id")
            return

        try:
            exit_obj = EvenniaExit.objects.get(id=int(exit_evennia_id))
            exit_obj.locks.add("traverse:all()")
            if exit_obj.db.err_traverse:
                del exit_obj.db.err_traverse
            if exit_obj.location:
                exit_obj.location.msg_contents(
                    f"The way {exit_obj.key} opens up, revealing a passable path."
                )
            logger.log_info(f"AgentCharacter: Unlocked exit '{exit_obj.key}' (id={exit_evennia_id})")
        except (EvenniaExit.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_unlock_exit: {e}")

    def _handle_create_object(self, command):
        """Create a generic object in a room."""
        from evennia import create_object
        from typeclasses.rooms import Room

        object_name = command.get('object_name')
        room_evennia_id = command.get('room_evennia_id')
        description = command.get('description', '')

        if not object_name or not room_evennia_id:
            logger.log_err(
                f"AgentCharacter._handle_create_object: missing fields "
                f"object_name={object_name}, room_evennia_id={room_evennia_id}"
            )
            return

        try:
            room = Room.objects.get(id=int(room_evennia_id))
            obj = create_object(
                "typeclasses.objects.Object",
                key=object_name,
                location=room,
            )
            if description:
                obj.db.desc = description
            logger.log_info(
                f"AgentCharacter: Created object '{object_name}' "
                f"(id={obj.id}) in room '{room.key}'"
            )
        except Exception as e:
            logger.log_err(f"AgentCharacter._handle_create_object: {e}")

    def _handle_hazard_msg(self, command):
        """Send a hazard message to a room."""
        from typeclasses.rooms import Room

        room_evennia_id = command.get('room_evennia_id')
        message = command.get('message', '')
        if not room_evennia_id or not message:
            logger.log_err("_handle_hazard_msg: missing room_evennia_id or message")
            return

        try:
            room = Room.objects.get(id=int(room_evennia_id))
            room.msg_contents(f"|R{message}|n")
        except (Room.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_hazard_msg: {e}")

    def _handle_hazard_damage(self, command):
        """Find characters in a room and send apply_damage messages to Minare for each."""
        from typeclasses.rooms import Room

        room_evennia_id = command.get('room_evennia_id')
        if not room_evennia_id:
            logger.log_err("_handle_hazard_damage: missing room_evennia_id")
            return

        try:
            room = Room.objects.get(id=int(room_evennia_id))
        except (Room.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_hazard_damage: {e}")
            return

        source_id = command.get('source_id', '')
        hardpoint_damage = command.get('hardpoint_damage', 0)
        vitality_damage = command.get('vitality_damage', 0)
        burn_duration = command.get('burn_duration', 0)
        burn_tick_damage = command.get('burn_tick_damage', 0)

        for obj in room.contents:
            if not (isinstance(obj, PlayerCharacter) or isinstance(obj, NonplayerCharacter)):
                continue
            domain_id = getattr(obj.db, 'minare_domain_id', None)
            if not domain_id:
                continue

            try:
                from server.conf.minare_client import get_minare_client
                client = get_minare_client()
                client.send_message({
                    "type": "apply_damage",
                    "character_id": domain_id,
                    "source_id": source_id,
                    "hardpoint_damage": hardpoint_damage,
                    "vitality_damage": vitality_damage,
                    "burn_duration": burn_duration,
                    "burn_tick_damage": burn_tick_damage,
                })
            except Exception as e:
                logger.log_err(f"_handle_hazard_damage: failed to send apply_damage: {e}")

    def _handle_flag_dead(self, command):
        """Flag a character as dead — apply death locks and notify the room."""
        character_evennia_id = command.get('character_evennia_id')
        if not character_evennia_id:
            logger.log_err("_handle_flag_dead: missing character_evennia_id")
            return

        try:
            from evennia.objects.models import ObjectDB
            char_obj = ObjectDB.objects.get(id=int(character_evennia_id))
            char_obj.db.is_dead = True
            char_obj.locks.add("cmd:false();move:false()")
            if char_obj.location:
                char_obj.location.msg_contents(
                    f"|R{char_obj.key} collapses, lifeless.|n",
                    exclude=[char_obj]
                )
                char_obj.msg("|RDarkness closes in. You have died.|n")
            logger.log_info(
                f"AgentCharacter: Flagged '{char_obj.key}' (id={character_evennia_id}) as dead"
            )
        except (ObjectDB.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_flag_dead: {e}")

    def _handle_flag_downed(self, command):
        """Flag a character as downed (incapacitated but alive)."""
        character_evennia_id = command.get('character_evennia_id')
        if not character_evennia_id:
            logger.log_err("_handle_flag_downed: missing character_evennia_id")
            return

        try:
            from evennia.objects.models import ObjectDB
            char_obj = ObjectDB.objects.get(id=int(character_evennia_id))
            char_obj.db.is_downed = True
            char_obj.locks.add("cmd:false()")
            if char_obj.location:
                char_obj.location.msg_contents(
                    f"|R{char_obj.key} collapses!|n",
                    exclude=[char_obj]
                )
                char_obj.msg("|RYou collapse, unable to continue.|n")
            logger.log_info(
                f"AgentCharacter: Flagged '{char_obj.key}' (id={character_evennia_id}) as downed"
            )
        except (ObjectDB.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_flag_downed: {e}")

    def _handle_flag_undowned(self, command):
        """Clear downed state — character has recovered."""
        character_evennia_id = command.get('character_evennia_id')
        if not character_evennia_id:
            logger.log_err("_handle_flag_undowned: missing character_evennia_id")
            return

        try:
            from evennia.objects.models import ObjectDB
            char_obj = ObjectDB.objects.get(id=int(character_evennia_id))
            char_obj.db.is_downed = False
            char_obj.locks.add("cmd:true()")
            if char_obj.location:
                char_obj.location.msg_contents(
                    f"|G{char_obj.key} stirs and gets back up.|n",
                    exclude=[char_obj]
                )
                char_obj.msg("|GYou pull yourself together and get back up.|n")
            logger.log_info(
                f"AgentCharacter: Cleared downed for '{char_obj.key}' (id={character_evennia_id})"
            )
        except (ObjectDB.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_flag_undowned: {e}")

    def _handle_combat_lock(self, command):
        """Lock a character's movement for combat."""
        character_evennia_id = command.get('character_evennia_id')
        if not character_evennia_id:
            logger.log_err("_handle_combat_lock: missing character_evennia_id")
            return
        try:
            from evennia.objects.models import ObjectDB
            char_obj = ObjectDB.objects.get(id=int(character_evennia_id))
            char_obj.db.in_combat = True
        except (ObjectDB.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_combat_lock: {e}")

    def _handle_combat_unlock(self, command):
        """Unlock a character's movement after combat."""
        character_evennia_id = command.get('character_evennia_id')
        if not character_evennia_id:
            logger.log_err("_handle_combat_unlock: missing character_evennia_id")
            return
        try:
            from evennia.objects.models import ObjectDB
            char_obj = ObjectDB.objects.get(id=int(character_evennia_id))
            char_obj.db.in_combat = False
        except (ObjectDB.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_combat_unlock: {e}")

    def _handle_npc_move(self, command):
        """Move an NPC to a random connected room via a random exit."""
        character_evennia_id = command.get('character_evennia_id')
        if not character_evennia_id:
            logger.log_err("_handle_npc_move: missing character_evennia_id")
            return

        try:
            from evennia.objects.models import ObjectDB
            import random
            char_obj = ObjectDB.objects.get(id=int(character_evennia_id))

            if not char_obj.location:
                return

            # Find all exits from current room
            exits = [
                obj for obj in char_obj.location.contents
                if obj.destination and obj.destination != char_obj.location
            ]
            if not exits:
                return

            chosen_exit = random.choice(exits)
            char_obj.move_to(chosen_exit.destination, quiet=False)
            logger.log_info(
                f"NPC '{char_obj.key}' wandered to {chosen_exit.destination.key}"
            )
        except (ObjectDB.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_npc_move: {e}")

    def _handle_npc_command(self, command):
        """Execute an arbitrary command as an NPC via execute_cmd."""
        character_evennia_id = command.get('character_evennia_id')
        cmd_string = command.get('command', '')
        if not character_evennia_id or not cmd_string:
            logger.log_err("_handle_npc_command: missing character_evennia_id or command")
            return

        try:
            from evennia.objects.models import ObjectDB
            char_obj = ObjectDB.objects.get(id=int(character_evennia_id))
            char_obj.execute_cmd(cmd_string)
            logger.log_info(
                f"NPC '{char_obj.key}' executed command: {cmd_string}"
            )
        except (ObjectDB.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_npc_command: {e}")

    def _handle_combat_msg(self, command):
        """Send a combat message to a room."""
        from typeclasses.rooms import Room

        room_evennia_id = command.get('room_evennia_id')
        message = command.get('message', '')
        if not room_evennia_id or not message:
            logger.log_err("_handle_combat_msg: missing room_evennia_id or message")
            return
        try:
            room = Room.objects.get(id=int(room_evennia_id))
            room.msg_contents(message)
        except (Room.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_combat_msg: {e}")

    def _handle_archive_object(self, command):
        """Archive an object — soft delete, invisible and untargetable."""
        object_evennia_id = command.get('object_evennia_id')
        message = command.get('message', '')
        if not object_evennia_id:
            logger.log_err("_handle_archive_object: missing object_evennia_id")
            return
        try:
            from evennia.objects.models import ObjectDB
            obj = ObjectDB.objects.get(id=int(object_evennia_id))
            if message and obj.location:
                obj.location.msg_contents(f"|R{message}|n")
            obj.archive()
            logger.log_info(
                f"AgentCharacter: Archived '{obj.key}' (id={object_evennia_id})"
            )
        except (ObjectDB.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_archive_object: {e}")

    def _handle_combat_feedback(self, command):
        """Send a personal combat message to a specific character."""
        character_evennia_id = command.get('character_evennia_id')
        message = command.get('message', '')
        if not character_evennia_id or not message:
            logger.log_err("_handle_combat_feedback: missing character_evennia_id or message")
            return
        try:
            from evennia.objects.models import ObjectDB
            char_obj = ObjectDB.objects.get(id=int(character_evennia_id))
            char_obj.msg(f"|w{message}|n")
        except (ObjectDB.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_combat_feedback: {e}")

    def _handle_vendor_buy(self, command):
        """Handle a vendor purchase: check currency stack, deduct, create/stack the item."""
        from evennia import create_object
        from typeclasses.items import Item, find_item_stack

        character_evennia_id = command.get('character_evennia_id')
        item_name = command.get('item_name', '')
        item_description = command.get('item_description', '')
        template_id = command.get('template_id', '')
        currency_template_id = command.get('currency_template_id', '')
        currency_name = command.get('currency_name', '')
        price = command.get('price', 0)

        if not character_evennia_id:
            logger.log_err("_handle_vendor_buy: missing character_evennia_id")
            return

        try:
            from evennia.objects.models import ObjectDB
            char_obj = ObjectDB.objects.get(id=int(character_evennia_id))

            # Find currency stack
            currency_stack = find_item_stack(char_obj, currency_template_id) if currency_template_id else None
            currency_count = (currency_stack.db.stack_count or 1) if currency_stack else 0

            if currency_count < price:
                char_obj.msg(f"|rYou don't have enough {currency_name} (need {price}, have {currency_count}).|n")
                return

            # Deduct currency
            if currency_stack.remove_from_stack(price):
                currency_stack.delete()

            # Create or stack purchased item
            existing = find_item_stack(char_obj, template_id)
            if existing:
                existing.add_to_stack(1)
            else:
                new_item = create_object(Item, key=item_name, location=char_obj)
                new_item.db.template_id = template_id
                new_item.db.desc = item_description

            char_obj.msg(f"|gYou receive {item_name}.|n")
        except (ObjectDB.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_vendor_buy: {e}")

    def _handle_vendor_sell(self, command):
        """Handle a vendor sale: remove from stack, create/stack currency."""
        from evennia import create_object
        from typeclasses.items import Item, find_item_stack

        character_evennia_id = command.get('character_evennia_id')
        template_id = command.get('template_id', '')
        item_name = command.get('item_name', '')
        currency_template_id = command.get('currency_template_id', '')
        currency_name = command.get('currency_name', '')
        payout = command.get('payout', 0)

        if not character_evennia_id:
            logger.log_err("_handle_vendor_sell: missing character_evennia_id")
            return

        try:
            from evennia.objects.models import ObjectDB
            char_obj = ObjectDB.objects.get(id=int(character_evennia_id))

            # Find item stack
            sell_stack = find_item_stack(char_obj, template_id)
            if not sell_stack:
                char_obj.msg(f"|rYou don't have a {item_name} to sell.|n")
                return

            # Remove one from stack
            if sell_stack.remove_from_stack(1):
                sell_stack.delete()

            # Add currency to existing stack or create new
            currency_stack = find_item_stack(char_obj, currency_template_id) if currency_template_id else None
            if currency_stack:
                currency_stack.add_to_stack(payout)
            else:
                currency = create_object(Item, key=currency_name, location=char_obj)
                currency.db.template_id = currency_template_id
                currency.db.stack_count = payout

            char_obj.msg(f"|gYou receive {payout} {currency_name}.|n")
        except (ObjectDB.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_vendor_sell: {e}")

    def _handle_create_item(self, command):
        """Create an item or add to existing stack in a room."""
        from evennia import create_object
        from typeclasses.items import Item, find_item_stack
        from typeclasses.rooms import Room

        room_evennia_id = command.get('room_evennia_id')
        item_name = command.get('item_name', '')
        item_description = command.get('item_description', '')
        template_id = command.get('template_id', '')

        if not room_evennia_id:
            logger.log_err("_handle_create_item: missing room_evennia_id")
            return

        try:
            room = Room.objects.get(id=int(room_evennia_id))
            existing = find_item_stack(room, template_id)
            if existing:
                existing.add_to_stack(1)
                logger.log_info(f"AgentCharacter: Stacked '{item_name}' in '{room.key}' (now x{existing.db.stack_count})")
            else:
                new_item = create_object(Item, key=item_name, location=room)
                new_item.db.template_id = template_id
                new_item.db.desc = item_description
                logger.log_info(f"AgentCharacter: Created item '{item_name}' in '{room.key}'")
        except (Room.DoesNotExist, ValueError) as e:
            logger.log_err(f"_handle_create_item: {e}")

    def _handle_batch(self, command):
        """Execute a list of sub-commands sequentially with staggered timing."""
        from twisted.internet import reactor
        commands = command.get('commands', [])
        logger.log_info(f"AgentCharacter: Executing batch of {len(commands)} commands")
        for i, sub_command in enumerate(commands):
            if i == 0:
                self.handle_agent_command(sub_command)
            else:
                reactor.callLater(0.15 * i, self.handle_agent_command, sub_command)

class NonplayerCharacter(Character):
    """
    An NPC. Owns a cmdset (talk, ask, etc.) that merges with a player's
    cmdset on proximity (same room by default, lock-overridable).
    Minare-driven but player-visible. Downstream of the room-history/LLM
    pipeline.
    """

    def at_object_creation(self):
        super().at_object_creation()
        from commands.default_cmdsets import NpcCmdSet
        self.cmdset.add(NpcCmdSet, persistent=True)

    def handle_agent_command(self, command):
        """Dispatch a structured command from Minare, same as AgentCharacter."""
        action = command.get('action', '')
        text = command.get('text', '')

        if action == 'say':
            self.location.msg_contents(f'{self.key} says, "{text}"')
        elif action in ('emote', 'pose'):
            self.location.msg_contents(f'{self.key} {text}')
        else:
            logger.log_warn(f"NonplayerCharacter: unknown action '{action}'")
