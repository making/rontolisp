# Hello World: `hello-java/`

[`Hello.java`](src/main/java/Hello.java) is a compact source file: `main` and nothing else. It is a Maven
project ([`examples/java/`](../README.md)):

```bash
cd examples/java/hello-java
mvn package               # target/hello-java.wasm
wasmtime run target/hello-java.wasm
mvn exec:exec             # JDK
```
