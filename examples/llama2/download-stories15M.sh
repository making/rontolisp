#!/bin/sh
# Fetches the checkpoint the llama2.c README demos -- stories15M.bin (60 MB, from
# Andrej Karpathy's tinyllamas on Hugging Face) -- and its tokenizer.bin (from
# the llama2.c repository) into this directory. Both are gitignored; the 1 MB
# stories260K.bin + tok512.bin pair is checked in beside this script.
# Run once: ./download-stories15M.sh
set -e
cd "$(dirname "$0")"
fetch() {
  if [ -f "$1" ]; then
    echo "$1: already present"
  else
    echo "downloading $1 ..."
    curl -fsSL -o "$1" "$2"
  fi
}
fetch stories15M.bin https://huggingface.co/karpathy/tinyllamas/resolve/main/stories15M.bin
fetch tokenizer.bin https://github.com/karpathy/llama2.c/raw/master/tokenizer.bin
echo "done."
