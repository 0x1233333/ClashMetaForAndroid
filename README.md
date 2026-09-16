# Clash Meta for Android · Smart 版

基于 [MetaCubeX/ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) 的定制分支:内核换成 [vernesong/mihomo](https://github.com/vernesong/mihomo)(Alpha)smart 内核,并让**标准订阅零改动自动获得 smart 策略组 + LightGBM 模型**。

## 这个分支做了什么

| 改动 | 说明 |
|---|---|
| smart 内核 | 子模块指向 `vernesong/mihomo@Alpha`(固定 `4bc3d49`),支持 `type: smart` 策略组、LightGBM 权重预测、节点权重排行 |
| 自动转换 | 启动配置时把 `url-test` / `fallback` / `load-balance` 组原地转成 `smart`(组名/成员/规则引用不动,`select` 组不碰);实现在 `core/src/main/golang/native/config/smart_adapt.go` |
| 内置模型 | `Model.bin`(vernesong 官方 LightGBM-Model,9.3MB)通过 `go:embed` 打进 `libclash.so`,启动时自动安装到内核目录;转换的组自动带 `uselightgbm: true`,无首次下载依赖 |
| 状态面板 | 主界面新增「Smart 运行状态」:内置 WebView 仪表盘(`app/src/main/assets/smart.html`),读取内核权重排行(`GET /group/{name}/weights`),内核自动在 `127.0.0.1:9090` 开 RESTful 控制器(仅回环) |
| 兼容性 | 包名 `com.github.metacubex.clash.smart`,与官方 CMFA 共存;无密钥环境构建时输出未签名 APK |

## 使用

1. 安装 Release 里的 APK(未签名包需自行签名;`*-signed.apk` 可直接装)。
2. 导入你原来的订阅,**不用改任何内容**。
3. 启动 VPN,主界面点「Smart 运行状态」查看节点权重排行(约 5 分钟出第一份数据;学习期排名波动属正常)。
4. 日志页可看 `[SmartAdapt]` / `[Smart]` 输出;把日志等级调到 Debug 可见每条连接的权重来源(`Model: [LightGBM]` = 模型预测)。

## 构建

1. `git submodule update --init --recursive`
2. JDK 17+、Android SDK(NDK 由 AGP 自动安装)、Go ≥1.26(**建议** [MetaCubeX 补丁版 Go](https://github.com/MetaCubeX/go/releases/tag/build),含 Android 运行时修复)
3. `local.properties`(可选):
   ```properties
   custom.application.id=com.github.metacubex.clash.smart
   remove.suffix=true
   ```
4. `./gradlew app:assembleMetaRelease` → `app/build/outputs/apk/meta/release/`

## CI

`.github/workflows/build-release.yaml`(来自上游)支持 `workflow_dispatch`,输入 `release-tag`(格式 `vX.Y.Z`,须未被占用)即可自动构建并发 Release(未签名 APK)。仓库需开启 Settings → Actions → Workflow permissions → **Read and write**。

## 致谢与许可

- [kr328](https://github.com/kr328) 及 [MetaCubeX](https://github.com/MetaCubeX) 的 ClashMetaForAndroid
- [vernesong](https://github.com/vernesong) 的 OpenClash / mihomo smart 内核 / LightGBM 模型
- 许可证沿用上游([AGPL-3.0](COPYING.txt) 等),本分支改动同样以 AGPL-3.0 开源
