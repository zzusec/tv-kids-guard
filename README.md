# 电视儿童时长管控（tv-kids-guard）

给 Android 电视设置每日使用时长，显示倒计时，到点锁屏，由家长用遥控器密码管理。
当前版本 `2.2.1`（versionCode `15`），包名 `com.family.tvguard`。

源码：https://github.com/zzusec/tv-kids-guard
下载：https://github.com/zzusec/tv-kids-guard/releases/latest

## 下载安装

1. 从上方下载页取得 `tv-kids-guard.apk`，复制到 U 盘，在电视上打开安装；也可用下面的 ADB 命令。
2. 打开应用，输入初始密码 `000000`，按提示强制改成只有家长知道的 **6 位数字密码**。
3. 到电视系统设置开启悬浮窗权限；若电视提供可用的无障碍服务授权页，同时开启本应用的无障碍服务。
4. 默认每天 30 分钟，进入面板按 `12` 可修改。缺少权限时，面板会提示。

```bash
adb connect <电视IP>:5555
adb install -r tv-kids-guard.apk
```

## 主要功能与遥控操作

- 每日额度 1–1440 分钟，按屏幕亮起时长计时，关屏暂停，跨天重置；右上角显示剩余时间。
- 到点锁屏，家长输入密码可解除当天限额，也可加时或恢复锁定；重启后保留当天用量。
- 后台定期检查更新并自动下载；安装前仍需家长确认，不会静默安装或自行弹出安装页。

打开桌面图标、验证家长密码后进入面板；锁屏时按 OK 进入家长验证。启用无障碍后，也可在非锁屏时连按 `00`。面板用数字操作：

- `00` 关闭面板。
- `11` 修改密码，需再次验证原密码。
- `12` 修改每日时长，输入分钟数后按 OK，不清零今天已用时长。
- `13` 检查更新；安装需再次输入家长密码，再输入 `99` 按 OK，最后在系统安装页确认。
- `22` 今天加时，输入分钟数后按 OK，当天累计最多 60 分钟。
- `33` 恢复锁定，撤销当天的密码解锁。
- `44` 停用防护，需再次验证密码，再输入 `99` 按 OK 确认。
- `99` 启用防护，仅在防护停用时显示。

官方更新保留原有设置与用量，并校验包名、版本、SHA-256 和签名；下载完成不等于安装完成。

## 自行构建

需要 JDK 17、Android SDK 35 / build-tools 35.0.0 和 Python 3；用 `ANDROID_HOME` 或本机 `local.properties` 的 `sdk.dir` 指定 SDK。
公开源码不需要生产私钥即可测试和构建：

```bash
./gradlew testDebugUnitTest assembleDebug
python3 -I -B -m unittest discover -s tools -p 'test_*.py' -v
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。GitHub Actions 执行相同检查，在 Artifacts 提供 `tv-kids-guard-community-debug-apk`。
开发版签名与正式版不同，不能覆盖正式版，也不能作为官方 OTA 包；要继续原版 OTA，须使用自己私有保存的原签名。

## 使用原签名发布（维护者）

生产私钥与配置仅留本机：`~/.config/tv-kids-guard/release.keystore`、`~/.config/tv-kids-guard/signing.properties`；默认读取此配置，也可用 `TVGUARD_SIGNING_PROPERTIES` 指定。
配置包含以下字段，按本机原签名填写，不要上传私钥或真实密码：

```properties
storeFile=/绝对路径/release.keystore
storePassword=<密钥库密码>
keyAlias=<原签名别名>
keyPassword=<私钥密码>
```

先递增版本号并保留原签名。以下三步依次构建、生成三件套、发布；最后一步需要已登录且有仓库发布权限的 `gh`：

```bash
TVGUARD_SIGNING_PROPERTIES="$HOME/.config/tv-kids-guard/signing.properties" ./gradlew testDebugUnitTest assembleRelease
python3 -I -B tools/release_update.py --apk app/build/outputs/apk/release/app-release.apk --out app/build/outputs/ota --notes '版本更新说明'
python3 -I -B tools/release_update.py --apk app/build/outputs/apk/release/app-release.apk --out app/build/outputs/ota --notes '版本更新说明' --publish
```

生成目录需为空或不存在；核对 `tv-kids-guard.apk`、`update.json`、`SHA256SUMS` 后再执行发布。官方 OTA 只在本地使用原私钥签名发布，CI 不生成官方升级包。

## 限制与许可

普通模式不能阻止系统强停、卸载或撤销权限，不承诺绝对防绕过。系统级限制需要固件支持 Device Owner；已确认索尼 BRAVIA 4K 2015（Android 7 / API 24）不支持，只能使用普通模式，不要为此恢复出厂。
计时依据是屏幕亮起，不判断是否有人观看；无障碍授权是否可用取决于电视固件。

采用 MIT 许可证，详见仓库 `LICENSE`。Copyright (c) 2026 zzusec。
