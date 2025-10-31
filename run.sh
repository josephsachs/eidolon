#!/bin/bash

# Parse command line arguments
WORKER_COUNT=${1:-1}

cd docker
docker compose down -v

cd ../minare
mvn package

cd ../docker

# Clean up all log files
rm -rf logs/*.log
rm -rf data/{db,redis,kafka}/*
chmod 755 data/{db,redis,kafka}

# Clean Evennia database and logs
rm -f ../evennia/server/evennia.db3
rm -rf ../evennia/server/logs/*

# Create log directory if it doesn't exist
mkdir -p logs

docker compose build --no-cache

# Re-initialize Evennia
docker run --rm -v $PWD/../evennia:/usr/src/game evennia/evennia evennia migrate

# Start services with specified worker count
WORKER_COUNT=${WORKER_COUNT} docker compose up -d --scale worker=${WORKER_COUNT}

# Follow logs for all services
docker compose logs -f