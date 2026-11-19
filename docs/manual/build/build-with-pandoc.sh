#!/usr/bin/env bash
# 方案 A（推荐）：Markdown + Pandoc → Word
# 前置：安装 pandoc（>= 3.x）；本机当前未安装时可先继续使用 build.sh。
set -euo pipefail

MANUAL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$MANUAL_DIR/build"
REF="$BUILD_DIR/reference.docx"
OUT="$BUILD_DIR/金地美家供应链云平台操作手册_V1.0_pandoc.docx"

if ! command -v pandoc >/dev/null 2>&1; then
  echo "未找到 pandoc。请先安装（Ubuntu/Debian: sudo apt install pandoc；macOS: brew install pandoc），" >&2
  echo "或改用 build.sh（纯标准库，不依赖 pandoc）。" >&2
  exit 1
fi

cd "$MANUAL_DIR"

# 1) 拼接章节（去掉手册专用 HTML 标记，避免 pandoc 输出注释）
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
: > "$BUILD_DIR/manual-pandoc.md"
for f in "${FILES[@]}"; do
  # 删除封面块（改用 pandoc 标题块）；其余手册专用标记直接去掉
  sed -e '/<!-- cover:start -->/,/<!-- cover:end -->/d' \
      -e 's/<!-- toc -->//g' \
      -e 's/<!-- pagebreak -->//g' "$f" >> "$BUILD_DIR/manual-pandoc.md"
  printf '\n\n' >> "$BUILD_DIR/manual-pandoc.md"
done

# 2) 生成/检查样式模板
if [[ ! -f "$REF" ]]; then
  echo "未找到 $REF，正在用 pandoc 默认模板生成..." >&2
  pandoc --print-default-data-file reference.docx > "$REF"
  echo "已生成默认 reference.docx。请按《手册格式要求》在 Word 中调整样式（正文/标题/表格/页眉页脚）后重新运行。" >&2
fi

# 3) 转换：目录、三级标题、章节编号
pandoc "$BUILD_DIR/manual-pandoc.md" \
  -o "$OUT" \
  --reference-doc="$REF" \
  --toc --toc-depth=2 \
  --number-sections \
  --columns=120 \
  --metadata title="金地美家供应链云平台操作手册" \
  --metadata subtitle="V1.0" \
  --metadata date="2026-11-19" \
  --metadata lang="zh-CN"

echo "已生成：$OUT"
