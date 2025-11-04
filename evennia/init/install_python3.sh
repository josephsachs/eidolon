#!/bin/bash -ex
cd /opt
wget https://www.python.org/ftp/python/3.11.0/Python-3.11.0.tgz
tar -xzvf Python-3.11.0.tgz

# /opt/python3.11.0
cd Python-3.11.0
./configure --enable-optimizations
make -j$(nproc)
make altinstall
rm -rf Python-3.11.0.tgz Python-3.11.0
cd ..

# /opt
alternatives --install /usr/bin/python3 python3 /usr/local/bin/python3.11 1
alternatives --set python3 /usr/local/bin/python3.11

python3 --version

echo 'export PATH=/usr/bin/python3:$PATH' >> ~/.bash_profile
source ~/.bash_profile