# 频段

红米 K50 Ultra（22081212C，高通，已 root）上的测频段工具。打开后自动测当前数据卡能驻留的 5G 和 4G 频段，勾选后可以锁定，也可以一键回到硬件默认。

手机上已经装过的包是仓库根目录的 `bandlock.apk`。

## 做什么

- 打开即测。5G 测 n1、n78、n77、n5、n8、n3、n28、n41；4G 测 B1、B3、B5、B8、B34、B38、B39、B40、B41。
- 每个频段记录是否驻留、RSRP、SINR，能上网时再测一次下行和到 `223.5.5.5` 的延迟。
- 测完后回到测试前的锁定，并勾上 5G、4G 里各自最快的一项，可以改勾选。
- **锁 5G**：只保留勾选的 NR SA 频段，网络模式设为仅 5G。
- **锁 4G**：只保留勾选的 LTE 频段，网络模式设为仅 4G。
- **恢复系统默认**：频段掩码回到调制解调器报告的硬件能力，制式回到 5G/4G/3G/2G 自动。

锁频走高通 QMI，二进制是 `app/src/main/assets/qcom-band-menu`。数据卡按 `gsm.sim.state` 里已加载的卡槽选择。这台机器上是卡槽 2 的中国电信。

## 编译

需要 Android SDK 34、build-tools 34.0.0 和 37.0.0（d8），以及 JDK 17 或更高。

```sh
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./build.sh
```

产物是仓库根目录的 `bandlock.apk`。`./build.sh` 如果本机没有 `debug.keystore`，会在本地生成一把仅用于安装的调试签名。这个文件不入库。

## 许可证

本仓库使用 [GNU GPL v3](LICENSE)。锁频二进制 `qcom-band-menu` 来自 [Fronsipswu/qcom-band-menu](https://github.com/Fronsipswu/qcom-band-menu)，同样是 GPL v3。

## 安装

这台小米需要用 root 安装，普通 `adb install` 会被系统拦下：

```sh
adb push bandlock.apk /data/local/tmp/bandlock.apk
adb shell su -c 'pm install -r /data/local/tmp/bandlock.apk'
```

应用通过 `/system/bin/su` 提权。APatch 的包配置在 `/data/adb/ap/package_config`，允许后才提得到权。包名是 `com.xunflash.bandlock`。
