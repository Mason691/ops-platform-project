#!/usr/bin/env bash
# 方案 C（本机可用的兜底）：Markdown 拼装 + 纯标准库 OOXML 生成 .docx
# 不依赖 pandoc / python-docx，生成后可用 Word/WPS 打开并更新目录域。
set -euo pipefail

MANUAL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$MANUAL_DIR/build"
OUT="$BUILD_DIR/金地美家供应链云平台操作手册_V1.0.docx"

cd "$MANUAL_DIR"

FILES=(
  00-front-matter.md
  01-account.md
  02-roles.md
  03-master-data.md
  04-supply-chain.md
  05-business-orders.md
  06-return-void.md
  07-workbench.md
  08-appendix.md
)

: > "$BUILD_DIR/manual.md"
for f in "${FILES[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "缺少章节文件：$f" >&2
    exit 1
  fi
  cat "$f" >> "$BUILD_DIR/manual.md"
  printf '\n\n' >> "$BUILD_DIR/manual.md"
done

python3 "$BUILD_DIR/md_to_docx.py" "$BUILD_DIR/manual.md" "$OUT"

echo "已生成：$OUT"
echo "提示：Word 打开后如目录未展开，右键目录 → 更新域（或按 F9）。"
