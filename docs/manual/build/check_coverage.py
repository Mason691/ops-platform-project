#!/usr/bin/env python3
"""手册交付自检：29 项覆盖、章节结构、截图数量、旧字段残留。"""
import glob
import os
import re
import sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHAPTERS = sorted(glob.glob(os.path.join(BASE, '0[1-7]-*.md')))
REQUIRED = ['功能说明', '适用角色', '操作入口', '前置条件', '操作步骤',
            '字段说明', '业务规则与校验', '操作结果', '异常与处理',
            '注意事项', '测试验证要点']
STALE = ['AUTO_APPROVED', 'tradeInAutoApproved', 'tradeInAutoApproveReason',
         'scm_trade_in_config', 'boss.jd99.com']


def check_structure():
    issues = []
    sections = 0
    for path in CHAPTERS:
        text = open(path, encoding='utf-8').read()
        for block in re.split(r'\n## ', text)[1:]:
            title = block.split('\n', 1)[0]
            if 'FAQ' in title:
                continue
            sections += 1
            missing = [k for k in REQUIRED if f'【{k}】' not in block]
            if missing:
                issues.append(f'{os.path.basename(path)} / {title}: 缺少 {missing}')
    return sections, issues


def check_checklist():
    path = os.path.join(BASE, '08-appendix.md')
    text = open(path, encoding='utf-8').read()
    rows = re.findall(r'^\|\s*(\d+)\s*\|[^|]+\|[^|]+\|\s*(✅|⚠️)', text, re.M)
    nums = {int(n) for n, _ in rows}
    missing = [i for i in range(1, 30) if i not in nums]
    return len(rows), missing


def check_screenshots():
    count = 0
    for path in sorted(glob.glob(os.path.join(BASE, '0*.md'))):
        count += len(re.findall(r'【截图\s+\d+-\d+：', open(path, encoding='utf-8').read()))
    return count


def check_stale():
    hits = []
    for path in sorted(glob.glob(os.path.join(BASE, '0*.md'))):
        text = open(path, encoding='utf-8').read()
        for token in STALE:
            if token in text:
                hits.append(f'{os.path.basename(path)}: {token}')
    return hits


def main():
    sections, struct_issues = check_structure()
    checklist, missing_items = check_checklist()
    screenshots = check_screenshots()
    stale = check_stale()
    print(f'章节结构化功能点：{sections}')
    print(f'交付检查表条目：{checklist}（缺少：{missing_items or "无"}）')
    print(f'截图占位：{screenshots}')
    print(f'旧字段残留：{stale or "无"}')
    if struct_issues:
        print('结构问题：')
        for issue in struct_issues:
            print(' -', issue)
    failed = bool(struct_issues or missing_items or stale)
    print('结果：' + ('FAIL' if failed else 'PASS'))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
