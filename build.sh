#!/bin/bash

cd docker

BUILD_MINARE=false
BUILD_EVENNIA=false

while getopts "me" flag; do
  case "${flag}" in
    m) BUILD_MINARE=true ;;
    e) BUILD_EVENNIA=true ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      exit 1
      ;;
  esac
done

if [ "$BUILD_MINARE" = true ]; then
  echo "Rebuilding Minare..."

  cd ../minare
  mvn clean package
  cd ../docker

  rm -rf logs/*.log
  rm -rf data/{db,redis,kafka}/*
  chmod 755 data/{db,redis,kafka}
  mkdir -p logs

  docker compose build --no-cache haproxy app-coordinator infra worker mongodb redis kafka kafka-ui
fi

if [ "$BUILD_EVENNIA" = true ]; then
  echo "Rebuilding Evennia..."

  docker compose down evennia
  rm -f ../evennia/server/evennia.db3
  rm -rf ../evennia/server/logs/*
  docker compose build --no-cache evennia
fi

if [ "$BUILD_MINARE" != true ] && [ "$BUILD_EVENNIA" != true ]; then
  echo "Usage: ./build.sh -m|-e"
  echo "  -m: Rebuild Minare services"
  echo "  -e: Rebuild Evennia service"
  exit 1
fi

echo "Build complete. Run './run.sh [WORKER_COUNT] -m|-e' to start services."