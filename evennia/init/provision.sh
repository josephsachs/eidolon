#!/bin/bash -ex
mkdir /var/log/userdata_process.log
touch /var/log/process.log

echo "Installing OpenSSL" >> /var/log/process.log 2>&1
touch /var/log/install_openssl.log
/srv/evennia/launch-data/install_openssl.sh >> /var/log/install_openssl.log 2>&1

echo "Installing Python 3.11" >> /var/log/process.log 2>&1
touch /var/log/install_python3.log
/srv/evennia/launch-data/install_python3.sh >> /var/log/install_python3.log 2>&1

echo "Updating pip" >> /var/log/process.log 2>&1
touch /var/log/install_pip.log
/srv/evennia/launch-data/install_pip.sh >> /var/log/install_pip.log 2>&1

echo "Installing Evennia" >> /var/log/process.log 2>&1
touch /var/log/install_evennia.log
/srv/evennia/launch-data/install_evennia.sh >> /var/log/install_evennia.log 2>&1

echo "Installing and configuring extra packages" >> /var/log/process.log 2>&1
touch /var/log/setup_extras.log
/srv/evennia/launch-data/setup_extras.sh >> /var/log/setup_extras.log 2>&1

echo "Starting Evennia server" >> /var/log/process.log 2>&1
touch /var/log/start_evennia.log
/srv/evennia/launch-data/start_evennia.sh >> /var/log/start_evennia.log 2>&1

echo "Process completed" >> /var/log/process.log 2>&1