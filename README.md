# Zf 传图

把尼康 Zf 中的 JPEG 原图，通过相机 Wi-Fi 保存到 Android 手机相册。

这个项目来自一个很具体的需求：拍完照片后，在手机上清楚地选片、预览，再把需要的原图导入相册，作为 SnapBridge 之外的一个选择。

**[下载 Android 安装包（0.5.7）](https://github.com/zhuuchaofan/ZfTransfer/releases/download/v0.5.7/ZfTransfer-0.5.7.apk)** · [版本说明与校验文件](https://github.com/zhuuchaofan/ZfTransfer/releases/tag/v0.5.7)

## 可以做什么

- 三列照片浏览，向后滚动自动加载更多；长按滑动选择多张。
- 默认加载清晰预览，支持双击、双指缩放，按需查看原图。
- 单张或批量导入 JPEG 原图到手机相册，显示下载进度。
- 依据导入记录和手机文件显示“已导入”；重复导入可取消、跳过或再次导入。
- 连接期间使用前台服务；后台连接效果还受手机省电设置和相机无线状态影响。

## 适用范围

- Android 10 及以上。
- 当前实测组合：**小米 14 + Nikon Zf**。
- 这是公开试用版本。其他手机、其他相机及固件组合尚未验证。
- 当前导入 JPEG，不支持 RAW、视频或相机远程拍摄。
- 大批量长时间传输、故障恢复与相机关闭后的长时连接仍需继续验证。

## 安装和连接

1. 下载上方 `.apk` 文件，在 Android 手机上打开并按系统提示允许安装。
2. 在相机上开启“连接至智能设备 → Wi-Fi 连接”，保持热点开启。
3. 手机连接相机热点后打开应用；也可以在应用设置中填写相机 Wi-Fi 名称和密码，并确认系统连接请求。
4. 等照片列表出现后选片，点击“导入原图”。照片保存在手机相册的 `Pictures/ZfTransfer`。
5. 如需后台保持连接，允许通知，并在手机应用省电设置中选择合适的后台策略。

公开 APK 使用独立发布签名。此前安装了开发测试签名包的用户，无法直接覆盖安装；请先保留现有应用与记录，确认迁移方案后再处理。不要仅为尝试升级就卸载或清除数据。

## 每次提交的自动编译

推送提交后，[GitHub Actions](https://github.com/zhuuchaofan/ZfTransfer/actions/workflows/android-apk.yml) 自动运行单元测试并生成 APK，PR 和手动运行也支持。同一分支连续推送时，优先完成最新一次。

打开对应成功运行，在 **Artifacts** 下载 `ZfTransfer-APK-…`，解压后得到 APK、SHA256 校验和与构建信息。安装包保留30天，测试报告保留14天；下载需要登录 GitHub。

这些是公开测试签名包，不能覆盖使用专用发布签名的 Releases 安装。普通试用请优先下载页首的 Releases 版本；自动编译不会自行发布或替换该版本，也不使用发布私钥。自动测试不能替代手机与相机实测。

## 开源与反馈

遵循 [GPL-3.0](ZfTransfer/LICENSE)。第三方归属见 [THIRD_PARTY.md](ZfTransfer/THIRD_PARTY.md)。

遇到问题可到 [Issues](https://github.com/zhuuchaofan/ZfTransfer/issues) 说明手机型号、Android 版本、相机型号和复现步骤。请不要公开 Wi-Fi 密码、私人照片或其他敏感信息。

开发资料：[构建与功能记录](ZfTransfer/README.md) · [架构说明](架构说明.md) · [实机验证边界](开发记录.md) · [发布构建](发布说明.md)
