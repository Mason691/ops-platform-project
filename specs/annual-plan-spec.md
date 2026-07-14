# 年度任务（Annual Plan）功能规格

## 概述
在"增值业务"模块下新增"年度任务"功能，管理集团及各子公司年度销售计划。

## 数据库
- 表 `ops_annual_plan`：子公司品类计划金额明细
- 汇总数据实时计算，不存储

## 权限
- 运营商/超管：完全 CRUD + 审核
- 子公司(城市合伙人)：只读

## 后端接口
| 方法 | 路径 | 权限 |
|------|------|------|
| GET | /api/ops/admin/annual-plans | 所有角色 |
| GET | /api/ops/admin/annual-plans/{id} | 所有角色 |
| POST | /api/ops/admin/annual-plans | 运营商 |
| PUT | /api/ops/admin/annual-plans/{id} | 运营商 |
| DELETE | /api/ops/admin/annual-plans/{id} | 运营商 |
| PUT | /api/ops/admin/annual-plans/{id}/approve | 运营商 |
| GET | /api/ops/admin/annual-plans/summary | 所有角色 |
| GET | /api/ops/admin/annual-plans/summary-by-category | 所有角色 |
| GET | /api/ops/admin/annual-plans/org-detail | 所有角色 |
| POST | /api/ops/admin/annual-plans/import | 运营商 |

## 前端
- 主平台：src/views/biz/AnnualPlan.vue (Element Plus)
- Ops H5：pages/admin/annual-plan.vue (uni-app)
- 路由：/ops/annual-plan
- 菜单：sys_menu 新增子菜单项
