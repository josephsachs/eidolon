# Dockerfile in top level/evennia/
FROM evennia/evennia:latest

# Set environment variables for non-interactive shell
ENV DEBIAN_FRONTEND=noninteractive

# Download the obligatory Linux build tools
RUN apk add build-base

RUN chmod 775 /usr/src/game/init /usr/src/game/init/*.sh
RUN bash /usr/src/game/init/install_python3.sh
RUN bash /usr/src/game/init/install_pip.sh
RUN bash /usr/src/game/init/install_openssl.sh
RUN bash /usr/src/game/init/install_evennia.sh


# Upgrade Evennia and its core dependency, Twisted, to the latest versions
# This is the best defense against known, low-level timing/protocol bugs.
# RUN pip install --upgrade evennia twisted

# Execute the database migration
# RUN evennia migrate

# Default entry point (assuming evennia start -l is still desired)
# RUN evennia start

RUN mkdir -p /usr/src/game/server/logs && chmod 777 /usr/src/game/server/logs

ENTRYPOINT ["/bin/bash", "-c", "chmod -R 777 /usr/src/game/server/logs 2>/dev/null; evennia migrate && evennia start -l"]