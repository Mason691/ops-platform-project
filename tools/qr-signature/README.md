# 收款码签名工具

收款码已改为 **jar 内置图片 + ECDSA P-256 非对称签名**，后台不再提供上传入口。

## 密钥保管（硬性要求）

- 私钥由开发/财务负责人离线保管，**不得提交到 Git、不得上传服务器、不得入库**；
- 建议保存在加密磁盘/硬件令牌中，文件权限 `600`；
- 公钥硬编码在 `QrCodeIntegrityService.PUBLIC_KEY_BASE64`，随代码发布；
- 私钥泄露或人员变动时，必须重新生成密钥对、替换公钥并发布新版本。

首次生成密钥对（只需一次）：

```bash
openssl ecparam -name prime256v1 -genkey -noout -out payment-qr-private.pem
chmod 600 payment-qr-private.pem
openssl ec -in payment-qr-private.pem -pubout -out payment-qr-public.pem
# 将 payment-qr-public.pem 去掉头尾换行后填入 PUBLIC_KEY_BASE64
```

## 更新二维码（只走发布流程）

```bash
# 1. 替换图片
cp /path/to/new-qr.png backend/src/main/resources/secure/qr/payment-qr.png

# 2. 用离线私钥签名（生成 payment-qr.png.sig）
./tools/qr-signature/sign-payment-qr.sh \
  backend/src/main/resources/secure/qr/payment-qr.png \
  /secure/offline/payment-qr-private.pem

# 3. 本地验证签名
openssl dgst -sha256 -verify /secure/offline/payment-qr-public.pem \
  -signature backend/src/main/resources/secure/qr/payment-qr.png.sig \
  backend/src/main/resources/secure/qr/payment-qr.png

# 4. 提交图片 + .sig，走代码评审/CI/发布
```

**禁止**在服务器上直接替换 `payment-qr.png` 或 `.sig`；jar 内资源更新必须重新打包发布，应用重启后启动校验会重新验签。

## 资源位置

- 图片：`backend/src/main/resources/secure/qr/payment-qr.png`
- 签名：`backend/src/main/resources/secure/qr/payment-qr.png.sig`

**不要**放到 `src/main/resources/static/` 下——Spring Boot 会自动静态暴露，绕过鉴权接口与验签。
