#!/bin/bash
source /home/ec2-user/.bash_profile

cd /srv/evennia
source evennia_venv/bin/activate

cd /srv/evennia/"$PROJECT_PATH"/server/conf
EVENNIA_SUPERUSER_USERNAME="$ADMIN_USERNAME" EVENNIA_SUPERUSER_PASSWORD="$ADMIN_PASSWORD" evennia start

deactivate
