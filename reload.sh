#!/bin/bash

cd docker

RELOAD_MINARE=false
RELOAD_EVENNIA=false

while getopts "me" flag; do
  case "${flag}" in
    m) RELOAD_MINARE=true ;;
    e) RELOAD_EVENNIA=true ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      exit 1
      ;;
  esac
done

if $RELOAD_MINARE; then
  echo "Reloading Minare..."
  cd ../minare
  mvn package -DskipTests
  cd ../docker
  docker compose restart app-coordinator worker
fi

if $RELOAD_EVENNIA; then
  echo "Reloading Evennia..."
  docker exec evennia evennia reload
fi

if ! $RELOAD_MINARE && ! $RELOAD_EVENNIA; then
  echo "Usage: ./reload.sh -m|-e"
  echo "  -m: Reload Minare services"
  echo "  -e: Reload Evennia service"
  exit 1
fi

docker compose logs -f