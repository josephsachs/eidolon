# Dockerfile in top level/evennia/
FROM evennia/evennia:latest

# Set environment variables for non-interactive shell
ENV DEBIAN_FRONTEND=noninteractive

# Upgrade Evennia and its core dependency, Twisted, to the latest versions
# This is the best defense against known, low-level timing/protocol bugs.
RUN pip install --upgrade evennia twisted

# Execute the database migration
RUN evennia-mud evennia migrate

# Execute the server setup script
RUN evennia-mud evennia serversetup

# Default entry point (assuming evennia start -l is still desired)
CMD ["evennia", "start", "-l"]