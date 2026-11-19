# 手册构建说明

本目录用于把 `docs/manual/*.md` 合成为可交付的 Word 手册。

## 当前已生成

- `金地美家供应链云平台操作手册_V1.0.docx` — 由 `build.sh`（纯标准库）生成，125 KB，含封面、目录域、页眉页脚、页码、标题/表格样式。
- 打开 Word 后如目录未展开：右键目录 → “更新域”（或按 F9）。

## 三种方案

### 方案 C（当前可直接执行，推荐本机先用）

```bash
cd docs/manual
bash build/build.sh
```

- 依赖：Python 3（标准库即可，无需 pandoc / python-docx）。
- 脚本：`build/md_to_docx.py`。
- 输出：`build/金地美家供应链云平台操作手册_V1.0.docx`。
- 支持：封面、目录域、一级标题自动分页、页眉、页脚页码、表格、引用、列表、行内加粗/代码。

### 方案 A（长期维护推荐）：Markdown + Pandoc

```bash
# 1) 安装 pandoc（任选）
sudo apt install pandoc          # Ubuntu/Debian
brew install pandoc              # macOS

# 2) 生成/准备样式模板
python3 build/make-reference-docx.py build/reference.docx
# 或：pandoc --print-default-data-file reference.docx > build/reference.docx
# 然后在 Word 中调整样式：正文宋体小四、标题黑体、Table Grid、页边距、页眉页脚

# 3) 转换
bash build/build-with-pandoc.sh
```

- 脚本：`build/build-with-pandoc.sh`。
- 输出：`build/金地美家供应链云平台操作手册_V1.0_pandoc.docx`。
- 说明：脚本会去掉手册专用 HTML 注释，使用 `--toc --toc-depth=3 --number-sections`。

### 方案 B：python-docx 直接生成

```bash
pip install python-docx
python3 build/make-docx-python-docx.py
```

- 脚本：`build/make-docx-python-docx.py`。
- 输出：`build/金地美家供应链云平台操作手册_V1.0_pythondocx.docx`。
- 支持：封面、目录域、标题 1-4、表格（表头底色）、引用、列表、页眉页脚、页码。

## 文档格式约定

| 项 | 约定 |
|---|---|
| 封面 | 手册名称、版本、日期、适用版本、密级、编写单位；`<!-- cover:start/end -->` |
| 修订记录 | `00-front-matter.md` 内表格 |
| 目录 | `<!-- toc -->`；Word 打开后更新域；取章/功能点两级（固定 11 段结构不进入目录，避免 400+ 条目） |
| 标题层级 | 章 `#`、功能模块 `##`、功能点 `###`、小节 `####` |
| 表格 | 表头加粗浅蓝底、全边框、自动列宽 |
| 页眉 | 金地美家供应链云平台操作手册 V1.0 |
| 页脚 | 第 X 页（PAGE 域） |
| 字体 | 正文宋体/微软雅黑小四；标题黑体；代码/字段名等宽 |
| 分页 | 一级标题自动分页；`<!-- pagebreak -->` 手动分页 |

## 目录与文件

| 文件 | 内容 |
|---|---|
| `00-front-matter.md` | 封面、修订记录、术语表、阅读说明 |
| `01-account.md` | 第 1 章 账号与登录 |
| `02-roles.md` | 第 2 章 权限与角色 |
| `03-master-data.md` | 第 3 章 基础资料 |
| `04-supply-chain.md` | 第 4 章 供应链管理 |
| `05-business-orders.md` | 第 5 章 业务数据订单 |
| `06-return-void.md` | 第 6 章 业务订单回退与作废 |
| `07-workbench.md` | 第 7 章 公众号员工工作台 |
| `08-appendix.md` | 附录 A 状态字典 / B 截图清单 / C 待确认 / D 交付检查表 |
| `OUTLINE.md` | 大纲、覆盖范围与编写进度 |
| `build.sh` | 方案 C 构建脚本（当前可用） |
| `md_to_docx.py` | 纯标准库 OOXML 转换器 |
| `build-with-pandoc.sh` | 方案 A 构建脚本 |
| `make-reference-docx.py` | 方案 A 样式模板生成 |
| `make-docx-python-docx.py` | 方案 B 生成脚本 |
| `check_coverage.py` | 交付自检：29 项覆盖、33 个结构化功能点、69 张截图、旧字段残留 |

## 校验

- `python3 -c "import zipfile,xml.etree.ElementTree as ET; z=zipfile.ZipFile('build/金地美家供应链云平台操作手册_V1.0.docx'); [ET.fromstring(z.read(n)) for n in z.namelist() if n.endswith('.xml')]"`
- 打开 Word/WPS 检查：封面、目录、页眉页脚、表格边框、页码。
- 截图占位共 69 张，见附录 B；补图后重新运行构建。
