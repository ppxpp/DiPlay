#!/usr/bin/env bash
# Provision the workspace-wide signing identity shared by DiPlay and WeCarFlow.
# Material lives outside every Git repository; only the symlinks and the keystore path are referenced by builds.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE="$(cd "$SCRIPT_DIR/../.." && pwd)"
SHARED="$WORKSPACE/.signing"
KEYSTORE="$SHARED/repack.keystore"
PROPERTIES="$SHARED/signing.properties"
ALIAS="carplay"
DN="CN=CarPlay Local, OU=Dev, O=Local, L=Local, ST=Local, C=CN"

command -v keytool >/dev/null || { echo "keytool not found; install a JDK first." >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl not found." >&2; exit 1; }

mkdir -p "$SHARED"
chmod 700 "$SHARED"

if [ -f "$KEYSTORE" ] && [ -f "$PROPERTIES" ]; then
    echo "Keystore already present; reusing it. Passwords are never regenerated."
else
    if [ -f "$KEYSTORE" ]; then
        echo "A keystore exists but signing.properties is missing, so its passwords cannot be recovered." >&2
        echo "Restore $PROPERTIES or replace $KEYSTORE deliberately; this script will not overwrite either." >&2
        exit 1
    fi
    STORE_PASS="$(openssl rand -base64 24 | tr -d '\n')"
    export STORE_PASS
    # PKCS12 protects the entry with the store password, so the key password is the same value.
    keytool -genkeypair -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -storetype PKCS12 -keystore "$KEYSTORE" -storepass:env STORE_PASS \
        -keypass:env STORE_PASS -dname "$DN"
    umask 077
    cat >"$PROPERTIES" <<EOF
storeFile=.signing/repack.keystore
storePassword=${STORE_PASS}
keyAlias=${ALIAS}
keyPassword=${STORE_PASS}
EOF
    unset STORE_PASS
    echo "Generated $KEYSTORE (alias $ALIAS)."
fi

chmod 600 "$PROPERTIES"
chmod 600 "$KEYSTORE"

link_into() {
    local project="$1"
    local relative="$2"
    [ -d "$project" ] || return 0
    if [ -L "$project/.signing" ] || [ ! -e "$project/.signing" ]; then
        ln -sfn "$relative" "$project/.signing"
        echo "Linked $project/.signing -> $SHARED"
    else
        echo "Keeping existing directory $project/.signing (not a symlink)."
    fi
}

link_into "$WORKSPACE/DiPlay" "../.signing"
link_into "$WORKSPACE/DiPlay/wecarflow" "../../.signing"

STORE_PASS="$(sed -n 's/^storePassword=//p' "$PROPERTIES")"
export STORE_PASS
FINGERPRINT="$(keytool -list -rfc -alias "$ALIAS" -keystore "$KEYSTORE" -storepass:env STORE_PASS \
    | sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' \
    | openssl x509 -noout -fingerprint -sha256 \
    | sed 's/.*=//' | tr -d ':' | tr 'A-F' 'a-f')"
unset STORE_PASS

echo
echo "Shared signing certificate SHA-256: $FINGERPRINT"
echo "Every DiPlay APK (debug and release) and the WeCarFlow repack must report this digest."
echo
echo "GitHub Secrets for DiPlay (.signing/ is created from these in CI):"
echo "  SIGNING_KEYSTORE_BASE64      $(base64 <"$KEYSTORE" | tr -d '\n')"
sed -n 's/^storePassword=/  SIGNING_KEYSTORE_PASSWORD  /p;
         s/^keyAlias=/  SIGNING_KEY_ALIAS            /p;
         s/^keyPassword=/  SIGNING_KEY_PASSWORD       /p' "$PROPERTIES"
echo
echo "Keep .signing/ outside Git. Rotating this key means uninstalling the installed apps on the head unit."
