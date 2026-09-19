# 1.0 — Flutter 原型（已弃用技术栈）

| 项目 | 值 |
|------|-----|
| APK | `石大课表_Flutter原型.apk`（57.0 MB） |
| 包名 | `com.shzu.shzu_class` |
| versionName / versionCode | `1.0` / `1` |
| 应用名 | 石大课表 |
| 构建时间 | 2026-09-18 20:41 |
| compileSdk / targetSdk | 36 / 36 |
| 技术栈 | Flutter + Dart + Dio + Provider |

## 定位

整个项目的**第一版原型**，也是唯一一个用 Flutter 写的版本。
课表数据靠 **App 内自己登录教务系统抓取**（不是 WebView 导入）：
学号密码 → CAS 统一身份认证 → 短信二次认证 → 抓强智教务课表页 → 解析入库。

后来因为「登录态维护成本高 + UI 达不到 MiuiX 质感」，在下一个版本被整体废弃，
源码 `shzu_class_app/`（Flutter 工程）与 `app_src/`（源码快照）一并弃用。

## 本版本做了什么

- **CAS 登录**：复现统一身份认证前端 `encrypt.js` 的 AES-CBC 加密
  （64 位随机前缀 + PKCS7 填充 + Base64）提交学号密码。
- **短信二次认证（MFA）**：`getDynamicCodeByReauth.do` 发验证码 → `reAuthSubmit.do` 提交。
- **课表抓取与解析**：请求 `xskb_list.do`，解析 `id="timetable"` 的周课表网格，
  从单元格 `kbcontent` 详情块提取课程名/教师/地点/节次/周次/班级。
- **本地存储与展示**：抓取结果序列化到本地，今日页 / 周课表页展示。

## 本版本修掉的最后一个 bug：401

用户反馈登录时报 `DioException [bad response]: 401`。

**根因**：Dio 开 `followRedirects: true` 时，CAS 重定向链
（`authserver → sso.jsp?ticket → jwgl`）**中间 302 响应里的 `Set-Cookie`
（jwgl 的 JSESSIONID）不会经过 `onResponse` 拦截器**，导致 jwgl 会话丢失，
后续访问 `xsMain.htmlx` / `xskb_list.do` 全部 401。

**修复**：

1. `api_client.dart`：`BaseOptions` 改 `followRedirects: false` +
   `validateStatus: (s) => s != null && s < 500`（接受 3xx/4xx 自己处理）；
   新增 `getFollowRedirects(url, {query})` 手动跟随重定向（最多 12 跳），
   保证每一跳都经过拦截器正确捕获 `Set-Cookie`；新增 `isRedirect()` 静态判断。
2. `auth_service.dart`：GET casLogin 检测到 302（已登录、有 TGT）直接返回 success；
   POST casLogin 后改用 `Location` 头判断走向（`reAuthLoginView`→需短信 /
   `ticket=`→成功 / 回到 authserver→失败），不再用 `realUri`（因为关了重定向）。
   `enterJwgl` 改用 `getFollowRedirects` 走完 casLogin→sso.jsp→jwgl 链路，
   并显式检测 401 返回友好错误。
3. `schedule_service.dart`：新增 `SessionExpiredException`，`fetchRaw` 检测 401/302 时抛出。
4. `schedule_provider.dart`：`login` 检查 `enterJwgl` 返回值；
   `refresh` 捕获 `SessionExpiredException` → 提示「登录已过期」并置 `loggedIn=false`。

## 遗留经验（后面用不上了，但值得记）

- Dio 手动管理 Cookie 时，**跨域 SSO 重定向链必须关 followRedirects 手动跟随**，
  否则中间 302 的 `Set-Cookie` 全丢。
- 判断登录流程走向，用响应头的 `Location`，不要用 `realUri`。
- `jwgl.shzu.edu.cn` 服务器 TLS 较旧，Python 标准 `requests`（OpenSSL 3.5+）握手会因
  后量子密钥交换 `X25519MLKEM768` 失败，需改用 `curl_cffi`（`impersonate="chrome"`）
  模拟 Chrome TLS 指纹——这条经验被 `../jwgl_spider.py` 继承了下来。
