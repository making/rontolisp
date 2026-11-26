# Sourced by ../sdk-examples/check.sh, with test_plugin running: what an error does on this SDK.

# A trap takes the VM down.
check "a failed host call fails the plugin" '^HTTP/1.1 5' "$url/failed-call"

# The SDK's default: the call answers nil, and the callback goes on.
start error_log --no-wasi
check "a refused call answers nil" '^x-outgoing: refused' "$url/"
check "a failed host call goes on" '^x-outgoing: refused' "$url/failed-call"
sleep 0.2
check_log "the log says a call failed" 'proxy-wasm: a host call answered status [0-9]+'
check "the VM lives after them" '^x-outgoing: refused' "$url/"
