import http.server, time, threading, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        time.sleep(1.5)
        b=b"origin says hi"
        self.send_response(200); self.send_header("Content-Length",str(len(b))); self.end_headers(); self.wfile.write(b)
    def log_message(self,*a): pass
s=http.server.ThreadingHTTPServer(("127.0.0.1",int(sys.argv[1])),H)
threading.Timer(int(sys.argv[2]), s.shutdown).start()
s.serve_forever()
