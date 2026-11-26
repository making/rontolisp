# Reading a Model Checkpoint (`gguf`, `safetensors`)

A published language model is three things in a directory: the weights, the
vocabulary its tokenizer was trained with, and the handful of hyperparameters
that say how the weights are wired. rontolisp reads all three with four shipped
packages, written in rontolisp itself and loaded on first use like
[`linalg`](linear-algebra.md) and [`geom`](solid-modeling.md) --
[`gguf`](../reference/functions/gguf.md) and
[`safetensors`](../reference/functions/safetensors.md) are the two container
readers, [`checkpoint`](../reference/functions/checkpoint.md) is the staging
half they share, and [`tokenizer`](../reference/functions/tokenizer.md) turns
the vocabulary a file carries into token ids. No Python, no conversion step, no
external dependency: what a Hugging Face model page downloads is read where it
lands.

This page is about how the four compose. The order below is the order a reader
meets them: pick a checkpoint, read its metadata without touching the weights,
build the tokenizer the file carries, stage the tensors at a width the backend
supports, and render the prompt the checkpoint expects.

## The two containers

**A GGUF is one file.** The hyperparameters, the tokenizer and the weights are
in it together, in the width the publisher chose --
`TinyLlama-1.1B-Chat-v1.0-f16.gguf`, `Qwen3.5-0.8B-BF16.gguf`,
`SmolLM2-135M-Instruct-Q8_0.gguf`. It is what a `*-GGUF` repository ships and
what `llama.cpp` runs. `gguf:read` answers every question about it from one
handle.

**A safetensors checkpoint is a directory.** `model.safetensors` -- or a
sharded `model.safetensors.index.json` naming several shard files -- holds the
weights and nothing else. Its hyperparameters are `config.json` beside it and
its vocabulary is `tokenizer.json` and `tokenizer_config.json`, each an ordinary
JSON file you read with
[`rontolisp:json-parse`](../reference/functions/rontolisp-json-parse.md). This
is what a Hugging Face model page downloads, and it is the file the publisher
trained and uploaded themselves; a GGUF of the same model is a conversion of it.

That difference is the whole shape of the two reads. A GGUF is one call and a
handful of accessors; a safetensors checkpoint is three reads you compose
yourself, and its halves can disagree -- which is the subject of
[the chat template](#the-chat-template-is-the-checkpoints) at the end of this
page.

Both formats are a header followed by tensor bytes at offsets from the end of
that header. A GGUF's header is a key/value block and then a tensor directory;
a safetensors file's is a little-endian `u64` length and that many bytes of
JSON, `{ "<name>": { "dtype": "BF16", "shape": [rows, cols], "data_offsets":
[begin, end] }, ... }`. Both readers walk their file front to back, and pass
over what they were not asked for with `checkpoint:skip-bytes` -- which seeks
past the bytes when the stream tells its position, and reads through them
otherwise.

## Reading the metadata is free

`(gguf:read path :metadata-only t)` stops after the tensor directory. The
hyperparameters and the entire tokenizer are already there, so it never touches
the gigabytes: it is how you inspect a checkpoint you have just downloaded, and
how you take its vocabulary.

```console
CL-USER> (defparameter *m* (gguf:read "SmolLM2-135M-Instruct-f16.gguf" :metadata-only t))
*M*
CL-USER> (list (gguf:version *m*)
               (gguf:metadata-value *m* "general.architecture")
               (gguf:metadata-value *m* "llama.block_count")
               (length (gguf:tensor-names *m*)))
(3 "llama" 30 272)
```

[`gguf:metadata`](../reference/functions/gguf-metadata.md) hands over the whole
key/value block as a hash table when you want to look at everything;
[`gguf:metadata-value`](../reference/functions/gguf-metadata-value.md) takes one
key and a default that really means absent;
[`gguf:tensor-info`](../reference/functions/gguf-tensor-info.md) is one tensor's
directory entry, whose dims are ROW-MAJOR rather than the file's own order.

The safetensors half of the same read is
[`safetensors:header`](../reference/functions/safetensors-header.md), which
parses the JSON header and says where the data starts, and
[`safetensors:entries`](../reference/functions/safetensors-entries.md), which
turns it into `(name dtype shape begin end)` in file order. The hyperparameters
are a separate `rontolisp:json-parse` of `config.json`.

**Skipping a tensor saves memory, and -- when the stream tells its position -- I/O too.** `gguf:read`'s `:only` is a list of
tensor names; `safetensors:read`'s `:only` is a predicate over the name. Either
way a tensor that is not wanted is never staged and never converted, and on a
seekable stream never even read: a multimodal checkpoint's vision tower costs
nothing when it is skipped -- but on a stream without a position it still costs
its bytes of reading, walked in bounded reads.

## The tokenizer the checkpoint carries

Two vocabulary kinds are in circulation, and `tokenizer:` puts them behind one
`tokenizer:encode` / `tokenizer:decode`:

- **Byte-level BPE**, the GPT-2 shape: token strings indexed by id, plus a
  ranked merge list, best rank first. SmolLM2, Qwen 2.5 / 3 / 3.5, Llama 3 and
  LFM2.5 use it.
  [`tokenizer:make-bpe`](../reference/functions/tokenizer-make-bpe.md) builds it.
- **SentencePiece style**: pieces with a score each, merged greedily by score.
  Llama 2 and TinyLlama use it.
  [`tokenizer:make-sentencepiece`](../reference/functions/tokenizer-make-sentencepiece.md)
  builds it.

**The package never opens a file.** The vocabulary is always an argument, which
is why `tokenizer:` reaches for nothing but the standard package and runs on
every backend, browser playground included. A vocabulary from anywhere will do
-- a checkpoint's, or one written out by hand:

```lisp
(defparameter *tk*
  (tokenizer:make-bpe
   #("<|endoftext|>" "h" "e" "l" "o" "Ġ" "w" "r" "d"
     "he" "hel" "hell" "hello" "Ġw" "Ġwo" "Ġwor" "Ġworl" "Ġworld")
   '("h e" "he l" "hel l" "hell o" "Ġ w" "Ġw o" "Ġwo r" "Ġwor l" "Ġworl d")
   :specials '("<|endoftext|>") :bos 0 :eos 0))
(tokenizer:decode *tk* (tokenizer:encode *tk* "hello world"))
; => "hello world"
```

From a GGUF the vocabulary comes out already in the shape the two constructors
take. [`gguf:tokenizer-fields`](../reference/functions/gguf-tokenizer-fields.md)
returns a plist of `:model` (`"gpt2"` = byte-level with merges, `"llama"` =
SentencePiece-style with scores), `:pre`, `:tokens`, `:scores`, `:merges`,
`:token-type`, `:bos` and `:eos`, surfaced unchanged rather than interpreted.
Since `:metadata-only t` already has all of them, taking a checkpoint's
tokenizer never reads its weights:

```console
CL-USER> (let* ((fields (gguf:tokenizer-fields *m*))
                (tk (tokenizer:make-bpe (getf fields :tokens) (getf fields :merges)
                                        :kind (getf fields :pre))))
           (tokenizer:encode tk "Once upon a time"))
(6403 1980 253 655)
```

From a safetensors directory it is `tokenizer.json`'s own `vocab` and `merges`
with its `added_tokens` filled in, and `tokenizer_config.json` for the rest.

**The pre-tokenizer is the half that is not data**, so it is a `:kind` argument
of its own and is exported as
[`tokenizer:pre-tokenize`](../reference/functions/tokenizer-pre-tokenize.md):
`:gpt2`, `:smollm` (every `\p{N}` character split off on its own), `:llama3`
(digits three at a time), `:qwen2` (one digit at a time) or `:qwen35` (a
combining mark stays with its letter) -- or the GGUF's own
`tokenizer.ggml.pre` string, which `make-bpe` accepts as it is. The ids only
follow once the cut is right:

```lisp
(list (tokenizer:pre-tokenize :qwen2 "in 2025")
      (tokenizer:pre-tokenize :llama3 "in 2025"))
; => (("in" " " "2" "0" "2" "5") ("in" " " "202" "5"))
```

`:specials` is the list of token strings matched whole, before pre-tokenization.
Give it **every** added token the file declares, whether or not the file flags
it special: Qwen 3 ships `<think>` and `</think>` unflagged, and a reader that
took only the flagged ones feeds a chat prompt's think block as `<th` `ink` `>`
-- three ids where the model expects one.

## The widths that load

A checkpoint is published in a width, and not every width loads everywhere.

**F32, F16 and BF16 become packed float arrays.** `:element-type` picks the
destination: `'single-float` (the default), `'double-float`, or `'bfloat16` on
the interpreter and the JVM -- every other backend refuses that width by name.
A BF16 tensor into a `'bfloat16` destination is the only pairing with no
conversion at all: the file's own two bytes an element, in one transfer.
Everything else is widened (or narrowed) as it streams, so a 2.2 GB BF16 file
becomes 4.4 GB of single floats and needs a few megabytes besides.

**A Q8_0 tensor becomes a quantized matrix** --
[`rontolisp:quantize`](../reference/functions/rontolisp-quantize.md)'s blocks,
read straight into place, its own bytes in one transfer, with `:element-type`
not applying to it. GGUF only, and the interpreter and the JVM only: the WASM
backends signal at that tensor. A tensor that has to be SPLIT once it is loaded
-- a fused `query | gate` projection, say -- is split by
[`rontolisp:quantized-rows`](../reference/functions/rontolisp-quantized-rows.md),
which gathers the rows named into a fresh matrix block for block, so the halves
stay quantized and no value is ever expanded.

**Every other quantized type is refused BY NAME when its body is asked for, and
never earlier.** A Q4_K_M checkpoint still opens, still lists its whole tensor
directory and still hands over its vocabulary; it fails only where you ask it
for a tensor it cannot give. The safetensors reader states the same rule the
other way round: any dtype outside F32 / F16 / BF16 signals an error naming the
tensor and the dtype.

The width is not a detail once the model runs. A decode step streams every
weight in the model exactly once, so what a token costs is the bytes the weights
occupy, and halving them is worth about what that says. What multiplies them is
the subject of [`--simd`](simd-acceleration.md), [`--blas`](blas-acceleration.md)
and [`--gpu`](gpu-acceleration.md).

## What `checkpoint` stages

You reach for [`checkpoint`](../reference/functions/checkpoint.md) directly only
to write a reader for a container these two packages do not cover. It is worth
knowing what it enforces, because the three facts behind it are the three ways a
hand-written loader goes wrong.

`checkpoint:make-tensor` is the one allocation path: `make-array
:element-type` answers a BOXED array for a type it does not know, so a tensor
allocated by hand can silently cost eight bytes an element, and `make-tensor`
checks what it got. `checkpoint:stage-float-bits` takes the STREAM, a count,
`:float16` or `:bfloat16`, and the destination -- never a whole staged vector --
because a packed `(unsigned-byte 16)` vector costs those same eight bytes an
element on the interpreter and the JVM, so a tensor staged whole would cost four
times its file size in temporaries; it reads a million elements at a time
through one buffer reused across every tensor of every file and widens each
chunk with `rontolisp:widen-float-bits`. `checkpoint:stage-float32` reads an F32
tensor straight in, and `checkpoint:skip-bytes` is how a front-to-back walk
passes over what it does not want.

## Which backends

`tokenizer:` runs everywhere, the browser playground included, because its
vocabulary is an argument rather than a file.

The two container readers need a filesystem and nothing else. F32, F16 and BF16
tensors land in the same packed float arrays on all four backends, with and
without `--simd`, and the checked-in fixtures of `examples/llm/` pin exactly
that on each of them.

Two things are narrower, and both refuse by name rather than quietly doing
something else: the `'bfloat16` element type and the Q8_0 quantized matrix are
the interpreter and the JVM. None of it needs a GPU -- `--gpu` changes what the
weights are multiplied by, not what reads them.

## The chat template is the checkpoint's

An instruct model is trained on a prompt of a particular shape, and it answers a
differently shaped prompt worse without saying so. **The checkpoint's own
template is the authority**: a GGUF carries it as the `tokenizer.chat_template`
metadata key, a Hugging Face directory as `tokenizer_config.json`'s
`chat_template`. Read it out of the file before assuming the family's -- in a
GGUF it is one `(gguf:metadata-value *m* "tokenizer.chat_template")` on the
metadata-only read.

The failure this prevents is not hypothetical. SmolLM2-Instruct's own template
unconditionally opens with a system turn -- `<|im_start|>system`, `You are a
helpful AI assistant named SmolLM, trained by Hugging Face`, `<|im_end|>` --
whenever the first message is not already one, while LFM2.5's renders no system
turn at all for the same single user turn. The two share every token of ChatML
and both have `<|im_start|>` in their vocabulary, so one generic ChatML
rendering looks right for both and is wrong for one: every SmolLM2-Instruct
answer produced that way was missing its system turn, and that was found by
diffing against the checkpoint's own template rather than by reading the output.
Qwen 3 and 3.5 add a third shape again -- an EMPTY `<think>` block is how their
template turns thinking off.

rontolisp does not render Jinja, so a program either carries the family's
rendering as a format control or reads the template out of the file and follows
it. Either way the file is what settles it.

## The worked engine

[`examples/llm/`](../../../examples/llm/README.md) is the whole path running:
Karpathy's `llama2.c` ported to one Lisp file, reading `.bin`, GGUF and Hugging
Face safetensors checkpoints through these four packages, with a table of layer
kinds instead of a fork per family. Its README is the record of which published
models it has been run against, on which backends, and what a token costs on
each. This page describes the packages; that one describes a program written
with them.
