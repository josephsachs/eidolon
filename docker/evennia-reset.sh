#!/bin/bash
# Trash database and logs, keep game code

echo "Resetting Evennia database and logs..."

# Remove database
rm -f ../evennia/server/evennia.db3

# Remove logs
rm -rf ../evennia/server/logs/*

# Recreate database
docker run -it --rm -v $PWD/../evennia:/usr/src/game evennia/evennia evennia migrate

# After the migrate step:
echo "Creating default superuser..."
docker run --rm -v $PWD/../evennia:/usr/src/game evennia/evennia \
  python manage.py shell -c "from django.contrib.auth import get_user_model; User = get_user_model(); User.objects.filter(username='admin').delete(); User.objects.create_superuser('admin', 'admin@localhost', 'password')"

echo "Evennia reset complete."