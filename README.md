# Windows Remote Desktop Launcher

JavaFX + Gradle で作成したWindows Remote Desktop Launcher.
SSHによる踏み台接続を1つのアプリケーションで完結させました.

## Build
```powershell
.\gradlew clean build
```

## Run
```powershell
.\gradlew run
```

## Package (MSI)
Wix Toolset v3.14 が必要です。
```powershell
.\gradlew clean jpackage 
```
