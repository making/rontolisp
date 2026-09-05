# gguf:read

`(gguf:read path &key only metadata-only element-type)`

Reads the GGUF checkpoint at `path` -- the single file a downloaded small language model most often is, carrying the hyperparameters, the tokenizer and the weights together -- and returns the value the other `gguf:` functions take.

`:metadata-only t` stops after the tensor directory. That is where the hyperparameters and the whole tokenizer already are, so it never touches the gigabytes: it is how you inspect a checkpoint, and how you take its vocabulary for [`tokenizer:make-bpe`](tokenizer-make-bpe.md). `:only` is a list of tensor names to load and skip the rest -- which saves memory and conversion but not I/O, because a rontolisp stream cannot seek and the skipped bytes are still read past. `:element-type` picks the packed float width every tensor lands in: `'single-float` (the default), `'double-float`, or `'bfloat16` on the interpreter and the JVM (every other backend refuses that width by name). A BF16 tensor into a `'bfloat16` destination is the file's own bytes in one transfer -- no widen at all -- while F32 and F16 tensors are narrowed into it as they stream.

**F32, F16 and BF16 tensors load into packed float arrays; a Q8_0 tensor loads as a quantized matrix** ([`rontolisp:quantize`](rontolisp-quantize.md) -- its own bytes, one transfer, `:element-type` does not apply to it; interpreter and JVM only, the WASM backends signal at that tensor). **Any other quantized tensor is refused BY NAME when its body is asked for, and never earlier** -- so a Q4_K_M checkpoint still opens, still lists its whole directory and still hands over its tokenizer.

```console
CL-USER> (defparameter *m* (gguf:read "SmolLM2-135M-Instruct-f16.gguf" :metadata-only t))
*M*
CL-USER> (list (gguf:metadata-value *m* "general.architecture")
               (gguf:metadata-value *m* "llama.block_count")
               (length (gguf:tensor-names *m*)))
("llama" 30 272)
CL-USER> (gguf:tensor (gguf:read "SmolLM2-135M-Instruct-f16.gguf"
                                 :only '("output_norm.weight"))
                      "output_norm.weight")
#f(1.7578125 1.8203125 1.78125 ...)
```
