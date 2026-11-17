# Adaptive Theme (Xposed)

Hook 系统自动亮度，按亮度条位置或环境光 lux 自动切换深/浅色模式。
作用域：系统框架。需要 Vector / LSPosed，Android 10 及以上。

## 签名（第一次构建前做一次）

Android 只允许用**同一个证书**签名的 APK 覆盖安装。GitHub Actions 每次都在全新的机器上运行，
如果不提供固定密钥，每次构建的 debug 签名都不一样，新 APK 会装不上（只能先卸载，配置也会丢）。

1. 生成密钥（任意装了 JDK 的环境，例如 Termux 里 `pkg install openjdk-17`）：

   ```sh
   keytool -genkeypair -v -keystore release.jks -alias adaptivetheme \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. 转成 base64：`base64 -w0 release.jks`

3. 在仓库 Settings → Secrets and variables → Actions 里添加四个 Secret：

   | Secret | 内容 |
   |---|---|
   | `KEYSTORE_BASE64` | 上一步输出的整串 base64 |
   | `KEYSTORE_PASSWORD` | 密钥库密码 |
   | `KEY_ALIAS` | `adaptivetheme` |
   | `KEY_PASSWORD` | 密钥密码（keytool 默认与密钥库密码相同） |

4. 把 `release.jks` 和密码另外备份好，**不要提交到仓库**。丢了就只能卸载重装。

没配置 Secret 时工作流仍会成功，但会给出警告，产物名为 `AdaptiveTheme-temporary-signature`。
如果你之前装过这种临时签名的版本，换成固定签名后需要先卸载一次。

本地构建同理：设置环境变量 `RELEASE_KEYSTORE_FILE`（密钥库的绝对路径）、
`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 后运行 `gradle assembleRelease`。

## 安装

1. 安装 APK，在 Vector 里启用模块，作用域勾选「系统框架」，重启。
2. 打开模块设置，开启调试日志，按日志里的实际读数校准阈值。
