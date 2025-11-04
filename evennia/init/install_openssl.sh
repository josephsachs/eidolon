#!/bin/bash -ex
cd /opt
wget https://ftp.openssl.org/source/openssl-1.1.1k.tar.gz
tar -xzvf openssl-1.1.1k.tar.gz

# /opt/openssl-1.1.1k
cd openssl-1.1.1k
./config --prefix=/usr --openssldir=/etc/ssl --libdir=lib no-shared zlib-dynamic
make
# TODO: make tests work
#make test TESTS='test_cms test_ssl_new' V=1
#exit 0
make install

echo 'export LD_LIBRARY_PATH=/usr/local/lib:/usr/local/lib64' >> /etc/profile.d/openssl.sh
source /etc/profile.d/openssl.sh
openssl version

cd ..

# /opt
rm -rf /opt/openssl-1.1.1k.tar.gz

