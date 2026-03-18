#!/bin/bash

WORKER_COUNT=${1:-1}
shift  # Remove the first argument so getopts can process flags

cd docker
docker compose stop

START_MINARE=false
START_EVENNIA=false
BUILD=false
BUILD_BASE=false

while getopts "mebB" flag; do
  case "${flag}" in
    m) START_MINARE=true ;;
    e) START_EVENNIA=true ;;
    b) BUILD=true ;;
    B) BUILD=true; BUILD_BASE=true ;;
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

build_evennia_base() {
  echo "Building Evennia base image (dependencies only)..."
  docker build -f ../evennia/Dockerfile.base -t eidolon-evennia-base ../evennia
}
build_evennia() {
  echo "Building Evennia..."
  rm -f ../evennia/server/evennia.db3
  # Build base image if it doesn't exist or -B was passed
  if $BUILD_BASE || ! docker image inspect eidolon-evennia-base >/dev/null 2>&1; then
    build_evennia_base
  fi
  docker compose build evennia
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
  echo "  -b: Build (uses cached base image for Evennia)"
  echo "  -B: Full rebuild (including Evennia base image with deps)"
  exit 1
fi

docker compose logs -f