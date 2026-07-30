# 使用 GitHub Actions 编译 APK

## 上传项目

1. 在 GitHub 新建仓库。
2. 将本目录中的所有文件上传到仓库根目录。仓库根目录应直接看到：
   - `gradlew`
   - `settings.gradle.kts`
   - `composeApp/`
   - `.github/workflows/build-apk.yml`
3. 不要只上传外层压缩包，也不要让项目再多嵌套一层目录。

## 开始编译

1. 打开仓库的 **Actions** 页面。
2. 选择 **Build Android APK**。
3. 点击 **Run workflow**。
4. 等待任务变成绿色。
5. 打开该次运行，在页面底部的 **Artifacts** 下载：
   `RefreshRateManager-1.2.0-debug`
6. 解压 Artifact，安装其中的：
   `RefreshRateManager-1.2.0-debug.apk`

## 说明

- 生成的是可直接安装的 Debug APK。
- 每个不同仓库/环境生成的 Debug 签名可能不同；若手机提示签名不一致，先卸载旧版再安装，或配置固定的 Release 签名。
- 项目使用 JDK 17、Gradle Wrapper 9.4.1、compileSdk/targetSdk 37。
