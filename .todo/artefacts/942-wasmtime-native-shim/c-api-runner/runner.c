// Standalone native runner: the precompiled (Cranelift AOT) module is linked in as data.
#include <stdio.h>
#include <stdlib.h>
#include <wasi.h>
#include <wasmtime.h>
extern const unsigned char payload[]; extern const size_t payload_len;
static void die(const char *m, wasmtime_error_t *e, wasm_trap_t *t) {
  wasm_byte_vec_t msg;
  if (e) wasmtime_error_message(e, &msg); else if (t) wasm_trap_message(t, &msg); else { fprintf(stderr, "%s\n", m); exit(1); }
  fprintf(stderr, "%s: %.*s\n", m, (int) msg.size, msg.data); exit(1);
}
int main(int argc, char **argv) {
  wasm_config_t *cfg = wasm_config_new();
  wasmtime_config_wasm_gc_set(cfg, true);
  wasmtime_config_wasm_function_references_set(cfg, true);
  wasmtime_config_wasm_exceptions_set(cfg, true);
  wasmtime_config_wasm_tail_call_set(cfg, true);
  wasm_engine_t *engine = wasm_engine_new_with_config(cfg);
  wasmtime_module_t *module = NULL;
  wasmtime_error_t *err = wasmtime_module_deserialize(engine, payload, payload_len, &module);
  if (err) die("deserialize", err, NULL);
  wasmtime_store_t *store = wasmtime_store_new(engine, NULL, NULL);
  wasmtime_context_t *ctx = wasmtime_store_context(store);
  wasi_config_t *wasi = wasi_config_new();
  wasi_config_inherit_stdin(wasi); wasi_config_inherit_stdout(wasi); wasi_config_inherit_stderr(wasi); wasi_config_inherit_env(wasi); wasi_config_set_argv(wasi, argc, (const char **) argv);
  if ((err = wasmtime_context_set_wasi(ctx, wasi))) die("wasi", err, NULL);
  wasmtime_linker_t *linker = wasmtime_linker_new(engine);
  if ((err = wasmtime_linker_define_wasi(linker))) die("link wasi", err, NULL);
  wasmtime_instance_t inst; wasm_trap_t *trap = NULL;
  if ((err = wasmtime_linker_instantiate(linker, ctx, module, &inst, &trap)) || trap) die("instantiate", err, trap);
  wasmtime_extern_t start;
  if (!wasmtime_instance_export_get(ctx, &inst, "_start", 6, &start)) die("no _start", NULL, NULL);
  err = wasmtime_func_call(ctx, &start.of.func, NULL, 0, NULL, 0, &trap);
  if (err) { int status; if (wasmtime_error_exit_status(err, &status)) return status; die("run", err, NULL); }
  if (trap) die("trap", NULL, trap);
  return 0;
}
