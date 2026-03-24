"""
Account

The Account represents the game "account" and each login has only one
Account object. An Account is what chats on default channels but has no
other in-game-world existence. Rather the Account puppets Objects (such
as Characters) in order to actually participate in the game world.
"""

from evennia.accounts.accounts import DefaultAccount, DefaultGuest


class Account(DefaultAccount):
    """
    Custom Account that integrates with Minare on login.

    On post-login, registers with Minare and launches the account EvMenu
    instead of auto-puppeting a character.
    """

    def at_account_creation(self):
        """
        Override to prevent Evennia's default character auto-creation.
        Characters are created explicitly through the EvMenu flow.
        """
        pass

    def at_post_login(self, session=None, **kwargs):
        """
        Called after login. Registers account with Minare, then
        launches the account menu (Create/Play/Quit).

        We intentionally do NOT call super().at_post_login() because
        that auto-puppets the last character. We handle puppeting
        explicitly through the EvMenu.
        """
        # Send session-tracking info that DefaultAccount normally handles
        self.msg(
            "\n|wWelcome to Eidolon.|n\n",
            session=session,
        )

        from server.conf.minare_client import get_minare_client

        client = get_minare_client()

        def on_account_registered(response):
            if response.get('status') != 'success':
                self.msg(
                    f"|rError registering with game server: {response.get('error', 'unknown')}|n",
                    session=session,
                )
                return

            account_data = response.get('account', {})
            self.db.minare_account_id = account_data.get('_id', '')
            self.db.minare_character_ids = account_data.get('characterIds', [])

            # Launch the account menu
            from evennia.utils.evmenu import EvMenu
            EvMenu(
                self,
                "world.account_menu",
                startnode="menunode_main",
                session=session,
                persistent=False,
            )

        client.send_with_callback(
            {
                'type': 'register_account',
                'evennia_account_id': str(self.id),
            },
            on_account_registered,
        )


class Guest(DefaultGuest):
    """
    This class is used for guest logins. Unlike Accounts, Guests and their
    characters are deleted after disconnection.
    """

    pass
