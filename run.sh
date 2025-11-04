#!/bin/bash

WORKER_COUNT=${1:-1}

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
      docker compose build --no-cache haproxy app-coordinator infra worker mongodb redis kafka kafka-ui evennia
      ;;
    e)
      echo "Rebuilding Evennia..."
      cd ../docker
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

cd ../docker
#/WORKER_COUNT=${WORKER_COUNT} docker compose up -d --scale worker=${WORKER_COUNT} haproxy app-coordinator infra worker mongodb redis kafka kafka-ui
#docker compose up -d evennia

docker compose logs -f