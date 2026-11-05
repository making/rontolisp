# Sourced by check.sh, with test_plugin running: what an error does on this SDK.

# A trap takes the VM down, so each of these has an Envoy to itself.
check "a failed host call fails the plugin" '^HTTP/1.1 5' "$url/failed-call"
sleep 0.2
check_log "the log says which call" 'proxy-wasm: proxy_set_buffer_bytes answered status [0-9]+'

start test_plugin
check "an error fails the plugin" '^HTTP/1.1 5' "$url/signal"

# The SDK's default: the callback answers its default, and the VM lives.
start error_log --no-wasi
check "a refused call answers nil" '^x-outgoing: refused' "$url/"
check "a failed host call answers the default" 'hello from upstream' "$url/failed-call"
sleep 0.2
check_log "the log says which call" 'proxy-wasm: proxy_set_buffer_bytes answered status [0-9]+'
check "an error answers the default" 'hello from upstream' "$url/signal"
sleep 0.2
check_log "the log says an error was signaled" 'proxy-wasm: a callback signaled an error'
check "the VM lives after them" '^x-outgoing: refused' "$url/"
