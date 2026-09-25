#!/bin/sh
# Открывает запертые описания сборки. Ключ — файл, который есть только у автора.
#     ./unlock.sh /path/to/showcase-key.txt
set -e
KEY="$1"
if [ -z "$KEY" ] || [ ! -f "$KEY" ]; then
  echo "укажите файл ключа: ./unlock.sh /path/to/showcase-key.txt" >&2
  exit 1
fi
find . -name "*.enc" -print | while read -r sealed; do
  plain="${sealed%.enc}"
  openssl enc -d -aes-256-cbc -pbkdf2 -iter 300000 -pass "file:$KEY" -in "$sealed" -out "$plain"
  echo "открыт $plain"
done
