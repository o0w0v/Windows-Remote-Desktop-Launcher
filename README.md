# Windows Remote Desktop Launcher

JavaFX + Gradle で作成したWindows Remote Desktop Launcher.
SSHによる踏み台接続を1つのアプリケーションで完結させました.

## Build
Wix Toolset v3.14 が必要です。
```powershell
.\gradlew clean build
```powershell

## Run
```powershell
.\gradlew run
```powershell

# Package (MSI)
```powershell
.\gradlew clean jpackage 
```powershell
