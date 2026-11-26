#!/usr/bin/env python3
"""Writes the checkpoint-tokenizer-check fixture: one tiny byte-level BPE tokenizer
in the two shapes a published checkpoint ships it -- tokenizer-check/tokenizer.json
+ tokenizer_config.json (the Hugging Face pair) and tokenizer-check.gguf (llama.cpp's
metadata block, no tensors) -- and prints the ids the Python `tokenizers` library
gives the check's texts, which are ../.expected/checkpoint-tokenizer-check.txt.

The point of the fixture is the ADDED tokens: <|im_start|> and <|im_end|> flagged
"special", <think> and </think> NOT flagged special -- the way Qwen3 ships its think
block -- and every one of them is matched whole by the reference implementation. In
the GGUF the same four are token types 3 (control) and 4 (user-defined).

Needs `pip install tokenizers`. Run from this directory.
"""
import json
import struct

from tokenizers import AddedToken, Tokenizer, decoders, models, pre_tokenizers, trainers

CORPUS = [
    "Once upon a time, there was a little girl named Lily.",
    "She loved to play outside in the park.",
    "One day, she saw a big, red ball in the sky.",
    "hi ok user assistant think",
    "user\nhi\n\nok\n",  # the newline byte must be in the vocabulary: with no unk
    # token the reference BPE silently DROPS a character it cannot spell
]
SPECIAL = ["<|im_start|>", "<|im_end|>"]
PLAIN = ["<think>", "</think>"]
TEXTS = [
    "Once upon a time",
    "<|im_start|>user\nhi<|im_end|>\n<think>\n\n</think>\n\nok",
    "think<think>ok",
]


def build():
    tok = Tokenizer(models.BPE())
    tok.pre_tokenizer = pre_tokenizers.ByteLevel(add_prefix_space=False)
    tok.decoder = decoders.ByteLevel()
    # the corpus alphabet only, not ByteLevel.alphabet(): 125 tokens, not 380
    trainer = trainers.BpeTrainer(vocab_size=300, special_tokens=SPECIAL)
    tok.train_from_iterator(CORPUS, trainer)
    tok.add_tokens([AddedToken(t, special=False, normalized=False) for t in PLAIN])
    return tok


def write_hf(tok):
    tok.save("tokenizer-check/tokenizer.json")
    config = {
        "bos_token": None,
        "eos_token": "<|im_end|>",
        "add_bos_token": False,
        "tokenizer_class": "PreTrainedTokenizerFast",
    }
    with open("tokenizer-check/tokenizer_config.json", "w", encoding="utf-8") as f:
        json.dump(config, f, indent=1)


# GGUF v3, little-endian: magic, version, n_tensors, n_kv, then the key/value block
U8, I8, U16, I16, U32, I32, F32, BOOL, STRING, ARRAY, U64 = range(11)


def gguf_string(s):
    b = s.encode("utf-8")
    return struct.pack("<Q", len(b)) + b


def gguf_kv(key, vtype, value):
    out = gguf_string(key) + struct.pack("<I", vtype)
    if vtype == STRING:
        out += gguf_string(value)
    elif vtype == U32:
        out += struct.pack("<I", value)
    elif vtype == BOOL:
        out += struct.pack("<B", 1 if value else 0)
    elif vtype == ARRAY:
        etype, items = value
        out += struct.pack("<IQ", etype, len(items))
        for item in items:
            out += gguf_string(item) if etype == STRING else struct.pack("<i", item)
    else:
        raise ValueError(vtype)
    return out


def write_gguf(tok):
    vocab = tok.get_vocab()
    tokens = [None] * len(vocab)
    for s, i in vocab.items():
        tokens[i] = s
    model = json.loads(tok.to_str())["model"]
    merges = [m if isinstance(m, str) else " ".join(m) for m in model["merges"]]
    types = [3 if t in SPECIAL else 4 if t in PLAIN else 1 for t in tokens]
    kvs = [
        ("general.architecture", STRING, "llama"),
        ("general.name", STRING, "checkpoint-tokenizer-check"),
        ("tokenizer.ggml.model", STRING, "gpt2"),
        ("tokenizer.ggml.pre", STRING, "gpt-2"),
        ("tokenizer.ggml.tokens", ARRAY, (STRING, tokens)),
        ("tokenizer.ggml.merges", ARRAY, (STRING, merges)),
        ("tokenizer.ggml.token_type", ARRAY, (I32, types)),
        ("tokenizer.ggml.eos_token_id", U32, vocab["<|im_end|>"]),
        ("tokenizer.ggml.add_bos_token", BOOL, False),
    ]
    body = b"GGUF" + struct.pack("<IQQ", 3, 0, len(kvs))
    for key, vtype, value in kvs:
        body += gguf_kv(key, vtype, value)
    with open("tokenizer-check.gguf", "wb") as f:
        f.write(body)


def main():
    tok = build()
    write_hf(tok)
    write_gguf(tok)
    vocab = tok.get_vocab()
    print("vocabulary", len(vocab), "special", [vocab[t] for t in SPECIAL], "plain",
          [vocab[t] for t in PLAIN])
    for text in TEXTS:
        print(tok.encode(text).ids)


if __name__ == "__main__":
    main()
