Test-only TLS material for `http::wire`'s tests (never trusted by a runner): `ca.pem`, a
self-signed P-256 root, and `localhost.pem` / `localhost-key.pem`, a server certificate it
issued for `localhost` and `127.0.0.1`. Valid until 2126. Regenerate with:

    openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out ca-key.pem
    openssl req -x509 -new -key ca-key.pem -sha256 -days 36500 -subj "/CN=rontolisp test CA" \
      -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out ca.pem
    openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out localhost-key.pem
    openssl req -new -key localhost-key.pem -subj "/CN=localhost" |
      openssl x509 -req -CA ca.pem -CAkey ca-key.pem -CAcreateserial -sha256 -days 36500 -out localhost.pem \
        -extfile <(printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost,IP:127.0.0.1\n')
