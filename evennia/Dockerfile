# Game image — built on top of the pre-built base.
# Build base first (only when deps change):
#   docker build -f Dockerfile.base -t eidolon-evennia-base .
FROM eidolon-evennia-base:latest

ENTRYPOINT ["/bin/bash", "-c", "\
  rm -f /usr/src/game/server/*.pid && \
  rm -f /usr/src/game/server/logs/*.log && \
  chmod -R 777 /usr/src/game/server/logs 2>/dev/null; \
  evennia makemigrations && \
  evennia migrate && \
  evennia start -l"]