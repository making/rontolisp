#!/bin/sh
# Fetches the four MNIST idx files into dataset/ and decompresses them
# (rontolisp has no gzip support, so the Lisp loaders read the raw idx
# files). The mirror is the same one the book's dataset/mnist.py uses.
# Run once before any MNIST example: ./download-mnist.sh
set -e
cd "$(dirname "$0")/dataset"
base=https://ossci-datasets.s3.amazonaws.com/mnist
for f in train-images-idx3-ubyte train-labels-idx1-ubyte \
         t10k-images-idx3-ubyte t10k-labels-idx1-ubyte; do
  if [ -f "$f" ]; then
    echo "$f: already present"
    continue
  fi
  echo "downloading $f.gz ..."
  curl -fsSL -o "$f.gz" "$base/$f.gz"
  gunzip -f "$f.gz"
done
echo "done."
