#!/bin/bash -ex
cd /opt
wget https://bootstrap.pypa.io/get-pip.py
python3 get-pip.py --user
pip3.11 --version