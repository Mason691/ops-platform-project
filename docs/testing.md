# 测试指南

## 本地浏览器调试
```
cd frontend && npm run build:h5
# 浏览器打开 dist/build/h5/ → 基础 UI 调试
# 微信 JSSDK 功能无法在普通浏览器测试
```

## 微信开发者工具测试
```
1. 打开微信开发者工具 → 公众号网页项目
2. 输入地址: https://a.15891589.xyz/ops/  (部署后)
3. 工具内模拟微信环境，JSSDK 调用有效
```

## 真机调试
```
1. Nginx location /ops/ 指向 frontend/dist/build/h5/
2. 微信访问 https://a.15891589.xyz/ops/
```

## 模拟第三方小程序跳转
- 商品详情 → 兑换 → 确认 → 应尝试跳转小程序
- H5环境: 改为跳转 /pages/order/detail 页面
- 小程序环境: 使用 wx.navigateToMiniProgram
