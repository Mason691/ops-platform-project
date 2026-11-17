#!/usr/bin/env bash
# 收款码签名工具（开发侧使用，私钥不得入库/入服务器）
#
# 用法：
#   ./sign-payment-qr.sh <payment-qr.png> <payment-qr-private.pem>
#
# 产物：
#   <payment-qr.png>.sig  —— ECDSA P-256 / SHA-256 签名，随图片一起提交
#
# 依赖：openssl（支持 EC prime256v1）
set -euo pipefail

IMG=${1:?usage: sign-payment-qr.sh <image.png> <private-key.pem>}
KEY=${2:?usage: sign-payment-qr.sh <image.png> <private-key.pem>}

[ -f "$IMG" ] || { echo "ERROR: image not found: $IMG" >&2; exit 1; }
[ -f "$KEY" ] || { echo "ERROR: private key not found: $KEY" >&2; exit 1; }

openssl dgst -sha256 -sign "$KEY" -out "${IMG}.sig" "$IMG"

PUB=$(mktemp)
trap 'rm -f "$PUB"' EXIT
openssl ec -in "$KEY" -pubout -out "$PUB" 2>/dev/null
openssl dgst -sha256 -verify "$PUB" -signature "${IMG}.sig" "$IMG"
sha256sum "$IMG"
echo "OK: ${IMG}.sig generated"
