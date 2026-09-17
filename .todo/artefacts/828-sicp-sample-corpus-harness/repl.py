import subprocess, sys
def t(src, args=("--source-language", "scheme"), timeout=60):
    try:
        p = subprocess.run(["java", "-jar", "ronto.jar", *args], input=src.encode(), capture_output=True, timeout=timeout)
        print("--- in :", repr(src)); print("    out:", repr(p.stdout.decode()[-700:])); print("    err:", repr(p.stderr.decode()[:300]), "exit:", p.returncode)
    except subprocess.TimeoutExpired: print("--- in :", repr(src), "TIMEOUT")
if __name__ == "__main__":
    for s in sys.argv[1:]: t(s)
