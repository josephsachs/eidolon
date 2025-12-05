#!/bin/bash

WORKER_COUNT=${1:-1}
shift  # Remove the first argument so getopts can process flags

cd docker

while getopts "me" flag; do
  case "${flag}" in
    m)
      echo "Rebuilding Minare..."
      docker compose down -v haproxy app-coordinator infra worker mongodb redis kafka kafka-ui evennia
      cd ../minare
      mvn package
      rm -rf logs/*.log
      rm -rf data/{db,redis,kafka}/*
      chmod 755 data/{db,redis,kafka}
      mkdir -p ../logs
      cd ../docker
      docker compose build --no-cache haproxy app-coordinator infra worker mongodb redis kafka kafka-ui
      ;;
    e)
      echo "Rebuilding Evennia..."
      rm -f ../evennia/server/evennia.db3
      rm -rf ../evennia/server/logs/*
      docker compose build --no-cache evennia
      ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      exit 1
      ;;
  esac
done

if [[ "$*" == *m* ]] && [[ "$*" == *e* ]]; then
  # Both flags: start Minare first, then Evennia
  WORKER_COUNT=${WORKER_COUNT} docker compose up -d --scale worker=${WORKER_COUNT} haproxy app-coordinator infra worker mongodb redis kafka kafka-ui
  docker compose up -d evennia
elif [[ "$*" == *m* ]]; then
  # Only Minare
  WORKER_COUNT=${WORKER_COUNT} docker compose up -d --scale worker=${WORKER_COUNT} haproxy app-coordinator infra worker mongodb redis kafka kafka-ui
elif [[ "$*" == *e* ]]; then
  # Only Evennia (assumes Minare is already running)
  docker compose up -d evennia
fi

docker compose logs -f