#!/bin/bash

WORKER_COUNT=${1:-1}
shift  # Remove the first argument so getopts can process flags

cd docker
docker compose stop

START_MINARE=false
START_EVENNIA=false
BUILD=false

while getopts "meb" flag; do
  case "${flag}" in
    m) START_MINARE=true ;;
    e) START_EVENNIA=true ;;
    b) BUILD=true ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      exit 1
      ;;
  esac
done

build_minare() {
  echo "Building Minare..."
  rm -rf logs/*.log
  rm -rf data/{db,redis,kafka}/*
  chmod 755 data/{db,redis,kafka}
  mkdir -p logs

  cd ../minare
  mvn clean package -DskipTests
  cd ../docker

  docker compose build --no-cache haproxy app-coordinator infra worker mongodb redis kafka kafka-ui
}
start_minare() {
  if $BUILD; then
    build_minare
  fi
  echo "Starting Minare..."
  WORKER_COUNT=${WORKER_COUNT} docker compose up -d --scale worker=${WORKER_COUNT} haproxy app-coordinator infra worker mongodb redis kafka kafka-ui
}

build_evennia() {
  echo "Building Evennia..."
  docker compose build --no-cache evennia
}
start_evennia() {
  if $BUILD; then
    build_evennia
  fi
  echo "Starting Evennia..."
  docker compose up -d evennia
}

if $START_MINARE && $START_EVENNIA; then
  start_minare
  start_evennia
elif $START_MINARE; then
  start_minare
elif $START_EVENNIA; then
  start_evennia
else
  echo "Usage: ./run.sh [WORKER_COUNT] -b -m|-e"
  echo "  -m: Start Minare services"
  echo "  -e: Start Evennia service"
  echo "  -b: Build"
  exit 1
fi

docker compose logs -f