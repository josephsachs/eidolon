#!/bin/bash

WORKER_COUNT=${1:-1}
shift  # Remove the first argument so getopts can process flags

cd docker

START_MINARE=false
START_EVENNIA=false

while getopts "me" flag; do
  case "${flag}" in
    m) START_MINARE=true ;;
    e) START_EVENNIA=true ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      exit 1
      ;;
  esac
done

if $START_MINARE && $START_EVENNIA; then
  echo "Starting Minare and Evennia..."
  WORKER_COUNT=${WORKER_COUNT} docker compose up -d --scale worker=${WORKER_COUNT} haproxy app-coordinator infra worker mongodb redis kafka kafka-ui
  docker compose up -d evennia
elif $START_MINARE; then
  echo "Starting Minare..."
  WORKER_COUNT=${WORKER_COUNT} docker compose up -d --scale worker=${WORKER_COUNT} haproxy app-coordinator infra worker mongodb redis kafka kafka-ui
elif $START_EVENNIA; then
  echo "Starting Evennia..."
  docker compose up -d evennia
else
  echo "Usage: ./run.sh [WORKER_COUNT] -m|-e"
  echo "  -m: Start Minare services"
  echo "  -e: Start Evennia service"
  exit 1
fi

docker compose logs -f