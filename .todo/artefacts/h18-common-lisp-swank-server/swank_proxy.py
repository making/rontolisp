"""A logging proxy for the SWANK wire protocol.

Listens on LISTEN_PORT, forwards each connection to TARGET_PORT, and appends every
framed message (6 hex digit octet length + UTF-8 payload) to LOG, one line each:
'>> payload' for editor-to-Lisp, '<< payload' for Lisp-to-editor.
"""
import socket
import sys
import threading

listen_port = int(sys.argv[1])
target_port = int(sys.argv[2])
log_path = sys.argv[3]
lock = threading.Lock()


def log(direction, payload):
    with lock:
        with open(log_path, "a", encoding="utf-8") as f:
            f.write(direction + " " + payload.replace("\n", "\\n") + "\n")


def pump(src, dst, direction):
    buf = b""
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                break
            dst.sendall(chunk)
            buf += chunk
            while len(buf) >= 6:
                length = int(buf[:6], 16)
                if len(buf) < 6 + length:
                    break
                log(direction, buf[6:6 + length].decode("utf-8", "replace"))
                buf = buf[6 + length:]
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


server = socket.socket()
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("127.0.0.1", listen_port))
server.listen(5)
while True:
    client, _ = server.accept()
    upstream = socket.create_connection(("127.0.0.1", target_port))
    threading.Thread(target=pump, args=(client, upstream, ">>"), daemon=True).start()
    threading.Thread(target=pump, args=(upstream, client, "<<"), daemon=True).start()
