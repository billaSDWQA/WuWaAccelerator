# 鳴潮 GPU 自動加速器（WuWaAccelerator）

這是一個針對 **Samsung Galaxy A55 5G（SM-A5560／Exynos 1480／Xclipse 530）** 的 Shizuku 工具。它只嘗試調整一個 GPU devfreq 節點，不修改 CPU、記憶體、網路、螢幕刷新率或遊戲檔案。

> **目前版本：0.6.0 測試版。** 本版重整了 Shizuku 執行身分、前景辨識、GPU 寫入診斷和還原流程。CI 通過只代表程式可編譯與單元測試通過；A55 實機寫入和遊戲幀率仍須另外驗證。

## 功能範圍

- 啟動後透過 Shizuku UserService 偵測鳴潮是否在前景。
- 鳴潮進入前景時，讀取 `/sys/class/devfreq/10e00000.sgpu/interface/min_freq`。
- 原始值低於測試下限時，先保存原始值，再嘗試把最低頻率設為 **750000 kHz（750 MHz）**，並讀回驗證。
- 鳴潮離開前景、手動停止，或服務銷毀時，嘗試還原原始值。
- 還原失敗時保留備份，不以猜測值覆寫；服務仍在執行時會重試。服務重新啟動時，也會先檢查並處理舊備份。

## 0.6.0 重構重點

- 啟動時確認 Shizuku UserService 身分為 shell UID 2000 或 root UID 0，其他身分拒絕寫入。
- 直接執行 `/system/bin/dumpsys activity activities`，並同時保留新版及舊版 resumed-activity 欄位，避免 Shell 管線提前退出造成漏判。
- 前景命令逾時、命令失敗、解析失敗分別回報；GPU 寫入例外和讀回不一致也會顯示具體原因。
- 寫入前檢查可讀取的 `max_freq` 和 `available_frequencies`，不對超過硬體上限或未列出的頻率步進提出請求。
- 備份先在暫存檔完成同步與校驗，再改名成正式備份；還原前後均要求可讀取並驗證原始值。
- 輪詢週期為 1.5 秒，啟動後立即做首次前景偵測；Shizuku UserService 版本號升至 5，以更新舊常駐服務。

## 重要限制

1. **750 MHz 是最低頻率請求，不是固定 GPU 頻率或穩定 60 FPS 保證。** GPU 仍受 max_freq、governor、系統排程及熱管理影響。
2. GPU 節點的寫入權限由手機核心與韌體決定。Shizuku 使用 shell 權限不等於 Root；即使 ADB 可以讀取，也不代表一定可以寫入。
3. 本程式不會寫入熱管理節點，也不會關閉系統熱保護；但提高最低頻率可能增加耗電與發熱，和 Samsung 驅動／溫控策略的實際互動尚未經實機驗證。若手機明顯過熱、降頻或不穩，請立即停止並還原。
4. 如果 Shizuku 被停止或服務程序遭系統終止，當下可能無法即時還原；備份檔會保留，重新連接後會先嘗試還原。
5. 若備份校驗錯誤或韌體指紋不一致，程式會拒絕猜測還原值。不要手動刪除備份檔來掩蓋問題。

## 安裝與使用

### 需求

- Android 8.0 或更新版本。
- 已啟動的 Shizuku，並允許本程式使用 Shizuku 權限。
- 本程式會檢查製造商及型號，只允許 Samsung SM-A5560；其他手機不會啟動 GPU 監控或寫入節點。

Shizuku 安裝與啟動方式請看官方指南：https://shizuku.rikka.app/guide/setup/

### 安裝 Debug APK

1. 開啟本專案 GitHub 的 **Actions → Android CI**。
2. 進入成功的執行紀錄，在 Artifacts 下載 `WuWaAccelerator-debug`。
3. 解壓縮 ZIP，安裝其中的 APK。
4. 啟動 Shizuku，再開啟本程式並授權。
5. 程式連上 Shizuku 後會自動開始監控；再開啟鳴潮。
6. 要停止時，按 **「停止並還原 GPU 設定」**，並確認狀態顯示已還原。

> GitHub Hosted Runner 每次建立的 Debug 簽章可能不同。若新版 APK 無法直接覆蓋舊版，需先解除安裝舊版再安裝新版。解除安裝前，應先在程式內按停止並確認 GPU 已還原。

## 還原與安全設計

- 只有在成功讀取並驗證原始值、備份檔寫入且校驗通過後，才會嘗試修改 GPU。
- 備份包含韌體指紋、原始最低頻率與 SHA-256 校驗值。
- 寫入後會讀回確認；若寫入失敗，立即嘗試還原。
- 還原必須通過備份校驗、韌體指紋比對及讀回比對，成功後才刪除備份。
- 任何失敗都不會改用硬編碼值猜測還原。

備份位置：`/data/local/tmp/WuWaAccelerator/gpu-state-v1`（由 Shizuku UserService 的 shell 身分管理）。

## 本機建置

需要 Android SDK、JDK 25（CI 使用版本）及本機 SDK 平台套件：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Debug APK 輸出位置：

`app/build/outputs/apk/debug/app-debug.apk`

## 驗收狀態

| 項目 | 狀態 |
|---|---|
| Debug APK 編譯與上傳 | 已成功 |
| 前景套件解析單元測試 | 新增 topResumedActivity 無效時的 legacy fallback 測試 |
| GPU 頻率策略單元測試 | 新增 max_freq／available_frequencies 邊界測試 |
| Android Lint | 本次 CI 會執行 |
| Release 變體建置 | 本次 CI 會執行 |
| A55 Shizuku 實際寫入 | 尚未實測 |
| 鳴潮前景偵測與離開還原 | 尚未實機驗證 |
| 長時間 60 FPS | 尚未實測 |

**不會把 CI 編譯成功當成實機驗收成功。**
