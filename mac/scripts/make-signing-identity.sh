#!/bin/bash
# One-time: creates the "AyPhone Local Signing" certificate and adds it to your login keychain,
# so every AyPhone build is signed with the same identity. macOS then keeps permissions such as
# Accessibility (used by ⌘V paste from the phone) across updates, instead of treating each
# build as a new app. No Apple account needed; it doesn't make the app notarized or trusted.
#
# Back up mac/signing/ privately (like the Android release key): DMGs signed with a different
# certificate would lose their users' permissions again.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="AyPhone Local Signing"
# Kept in its own keychain with its own password, so builds can unlock and use it without
# a macOS password prompt (the login keychain asks once per tool, which scripts can't answer).
KEYCHAIN="$PWD/signing/ayphone-signing.keychain-db"
if [ -f "$KEYCHAIN" ]; then
    echo "\"$NAME\" is already set up in $KEYCHAIN."
    exit 0
fi
mkdir -p signing
cd signing
if [ ! -f ayphone-signing.p12 ]; then
    PASS=$(openssl rand -hex 16)
    cat > cert.cnf <<CNF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $NAME
O = AyPhone
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
CNF
    /usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 3650 -config cert.cnf 2>/dev/null
    /usr/bin/openssl pkcs12 -export -inkey key.pem -in cert.pem -name "$NAME" -out ayphone-signing.p12 -passout "pass:$PASS" 2>/dev/null
    printf 'p12Password=%s\n' "$PASS" > signing.properties
    rm key.pem cert.cnf
fi
PASS=$(sed -n 's/^p12Password=//p' signing.properties)
security create-keychain -p "$PASS" "$KEYCHAIN"
security set-keychain-settings "$KEYCHAIN" # no auto-lock timeout
security unlock-keychain -p "$PASS" "$KEYCHAIN"
security import ayphone-signing.p12 -k "$KEYCHAIN" -P "$PASS" -T /usr/bin/codesign
security set-key-partition-list -S apple-tool:,apple: -s -k "$PASS" "$KEYCHAIN" >/dev/null
echo "Set up \"$NAME\". Rebuild AyPhone, then allow it in Accessibility once more; after that it sticks."
