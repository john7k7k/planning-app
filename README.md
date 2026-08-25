# 任務管理商城 Android App

以 Android Studio 開啟此資料夾後，等待 Gradle 同步完成，即可用模擬器或 Android 手機執行。

核心功能：

- Room / SQLite 本機永久保存：任務模板、每日實例、前置條件、錢包、交易與兌換紀錄
- 單次、每日、每週、每月、間隔式任務；重複任務以各日期的 Task Instance 個別結算
- 0–100% 小數完成度、任務結果、按比例發放金幣與鑽石
- 任務前置條件、ALL / ANY 邏輯、最低完成度門檻與循環依賴防護
- 自訂獎勵商城、商品解鎖條件、限購規則與原子化兌換交易
- Dashboard、任務庫、商城、交易紀錄與錢包手動調整

架構：Kotlin、Jetpack Compose、Material 3、MVVM、Repository Pattern、Room、Coroutines、StateFlow、DataStore（預留設定擴充）。貨幣與完成度核心計算採 `BigDecimal`，並以 Room TypeConverter 的字串形式保存，避免浮點誤差。

需求：Android Studio（含 Android SDK 35）與 JDK 17。

# 編譯
在專案根目錄 C:\Users\user\Documents\逆襲計畫APP 開啟 PowerShell 後，使用：
## 1. Debug APK
.\gradlew.bat assembleDebug
輸出位置：
app\build\outputs\apk\debug\app-debug.apk
## 2. Release APK（需已建立 keystore.properties）
.\gradlew.bat assembleRelease
輸出位置：
app\build\outputs\apk\release\app-release.apk
## 3. Release AAB
.\gradlew.bat bundleRelease
輸出位置：
app\build\outputs\bundle\release\app-release.aab
若 PowerShell 顯示 Java 問題，先執行：
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot'