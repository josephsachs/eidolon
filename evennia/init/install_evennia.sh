#!/bin/bash
source /home/ec2-user/.bash_profile

cd /srv/evennia
if ! command -v virtualenv &>/dev/null; then
    echo "virtualenv is not installed. Installing virtualenv..."
    pip3.11 install virtualenv
fi

mkdir -p "$INSTALL_DIR"
cd "$INSTALL_DIR" || exit
virtualenv evennia_venv
source evennia_venv/bin/activate

pip3.11 install evennia[extra]
evennia --init "$PROJECT_PATH"
cd "$PROJECT_PATH" || exit

# TODO: Parameterize
echo "from django.contrib.auth import get_user_model; User = get_user_model(); User.objects.create_superuser('"$ADMIN_USERNAME"', '', '"$ADMIN_PASSWORD"')" | evennia shell

# Setup database
chmod -R u+w /srv/evennia/"$PROJECT_PATH"/server
chmod u+w /srv/evennia/"$PROJECT_PATH"/server/evennia.db3
# TODO: Parameterize

EVENNIA_SUPERUSER_USERNAME="$ADMIN_USERNAME" EVENNIA_SUPERUSER_PASSWORD="$ADMIN_PASSWORD" evennia migrate

deactivate
