# Build & Install

## Download a prebuilt binary (recommended)

Prebuilt native-image binaries are published on the
[GitHub releases page](https://github.com/making/rontolisp/releases/tag/0.1.0-SNAPSHOT).
They start instantly and need no JVM installed. Pick the asset for your platform,
make it executable, and put it on your `PATH` as `rontolisp`.

macOS (Apple Silicon):

```bash
wget https://github.com/making/rontolisp/releases/download/0.1.0-SNAPSHOT/rontolisp-darwin-arm64
chmod +x rontolisp-darwin-arm64
sudo mv rontolisp-darwin-arm64 /usr/local/bin/rontolisp
```

Linux (x86-64):

```bash
wget https://github.com/making/rontolisp/releases/download/0.1.0-SNAPSHOT/rontolisp-linux-amd64
chmod +x rontolisp-linux-amd64
sudo mv rontolisp-linux-amd64 /usr/local/bin/rontolisp
```

Linux (ARM64):

```bash
wget https://github.com/making/rontolisp/releases/download/0.1.0-SNAPSHOT/rontolisp-linux-arm64
chmod +x rontolisp-linux-arm64
sudo mv rontolisp-linux-arm64 /usr/local/bin/rontolisp
```

Verify the install:

```bash
rontolisp --version
```

The rest of the documentation uses the `rontolisp` command. If you build from
source instead (below), substitute `java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar`
for `rontolisp`.

## Build from source

```bash
./mvnw clean package
```

This produces `target/rontolisp-0.1.0-SNAPSHOT-exec.jar`, an executable JAR with
all dependencies included. Run it with `java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar`.

### Native Image (GraalVM)

Build a native executable yourself using GraalVM:

```bash
./mvnw -Pnative clean package
```

This produces `target/rontolisp`, a standalone native binary with instant startup.

**Requirements:**
- GraalVM 25+ (with `native-image` tool)

**Usage:**

```bash
# REPL
rontolisp

# File interpretation
rontolisp program.lisp

# Compile to JVM bytecode
rontolisp hello.lisp -o Hello.class

# Compile to WASM
rontolisp hello.lisp -o hello.wasm
```
