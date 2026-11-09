# 鳴潮 GPU 自動加速器（WuWaAccelerator）

這是一個針對 **Samsung Galaxy A55 5G（SM-A5560／Exynos 1480／Xclipse 530）** 的 Shizuku 工具。它只嘗試調整一個 GPU devfreq 節點，不修改 CPU、記憶體、網路、螢幕刷新率或遊戲檔案。

> **目前版本：0.8.0 測試版。** 本版重整了 Shizuku 執行身分、前景辨識、GPU 寫入診斷和還原流程。CI 通過只代表程式可編譯與單元測試通過；A55 實機寫入和遊戲幀率仍須另外驗證。

## 功能範圍

- 啟動後透過 Shizuku UserService 偵測鳴潮是否在前景。
- 鳴潮進入前景時，讀取 `/sys/class/devfreq/10e00000.sgpu/interface/min_freq`。
- 依 GPU 負載，在硬體允許的情況下動態請求 **750 / 864 / 960 / 1025 MHz** 的最低頻率；每次寫入均讀回驗證，並保留最初原始值供還原。
- 鳴潮離開前景、手動停止，或服務銷毀時，嘗試還原原始值。
- 讀取 Android `thermalservice` 節流等級；中度或更高、或溫度資料不可用時，停止加速並還原原始值。輕度狀態限制在 750 MHz 基準層。
- GPU 負載連續取樣後才升頻，降頻採較慢的確認次數，避免負載短暫波動造成頻率反覆切換。
- 讀取 `/proc/meminfo` 的 `MemAvailable` 作為記憶體壓力訊號；壓力升高時限制升頻，嚴重不足時停止加速。不會強制殺除其他 App 或系統程序。
- 還原失敗時保留備份，不以猜測值覆寫；服務仍在執行時會重試。服務重新啟動時，也會先檢查並處理舊備份。

## 0.7.0 自適應加速重點

- 啟動時確認 Shizuku UserService 身分為 shell UID 2000 或 root UID 0，其他身分拒絕寫入。
- 使用不帶提前退出條件的 `grep` 擷取 `dumpsys activity activities` 中的新舊 resumed-activity 欄位，再由解析器優先判讀新版、失敗時回退舊版。
- 前景命令逾時、命令失敗、解析失敗分別回報；GPU 寫入例外和讀回不一致也會顯示具體原因。
- 寫入前檢查可讀取的 `max_freq` 和 `available_frequencies`，不對超過硬體上限或未列出的頻率步進提出請求。
- 備份先在暫存檔完成同步與校驗，再改名成正式備份；還原前後均要求可讀取並驗證原始值。
- 前景偵測輪詢週期調整為 2 秒；Android thermalservice 約每 5 秒讀取一次，避免高頻率反覆呼叫 dumpsys。
- GPU 負載達 70%／90%／97% 時，依序考慮 864／960／1025 MHz；實際目標仍受 `max_freq` 與 `available_frequencies` 限制，不支援時自動退到較低步進。
- 升頻需連續兩次取樣，降頻需連續三次；Android 熱狀態升至 LIGHT 或記憶體壓力升高時立即限制到基準層。
- Shizuku UserService 版本號升至 6，以確保舊常駐服務被更新。

## 重要限制

1. **750–1025 MHz 是依條件提出的最低頻率請求，不是固定 GPU 頻率或穩定 60 FPS 保證。** GPU 實際頻率仍受 max_freq、governor、系統排程及系統熱管理影響。
2. GPU 節點的寫入權限由手機核心與韌體決定。Shizuku 使用 shell 權限不等於 Root；即使 ADB 可以讀取，也不代表一定可以寫入。
3. 本程式只讀取 Android thermalservice 狀態，不寫入熱管理節點，也不關閉系統熱保護。熱狀態達 MODERATE 或資料無法讀取時會還原原始 GPU 下限。溫度狀態是系統節流等級，不等同於單一外殼攝氏溫度；A55 長時間遊戲仍須實機校準。
4. 記憶體壓力管理只限制本程式提出的 GPU 頻率請求，不會殺除其他 App；背景程序生命週期交由 Android 管理。
5. 如果 Shizuku 被停止或服務程序遭系統終止，當下可能無法即時還原；備份檔會保留，重新連接後會先嘗試還原。
6. 若備份校驗錯誤或韌體指紋不一致，程式會拒絕猜測還原值。不要手動刪除備份檔來掩蓋問題。

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

## 驗收狀態（0.7.0）

| 項目 | 狀態 |
|---|---|
| Debug APK 編譯與上傳 | 已成功 |
| 前景套件解析單元測試 | 新增 topResumedActivity 無效時的 legacy fallback 測試 |
| GPU 頻率策略單元測試 | 涵蓋硬體頻率邊界、自適應升降頻、熱狀態解析、記憶體壓力與遲滯控制 |
| Android Lint | 本次 CI 會執行 |
| Release 變體建置 | 本次 CI 會執行 |
| A55 Shizuku 實際寫入 | 尚未實測 |
| 鳴潮前景偵測與離開還原 | 尚未實機驗證 |
| A55 thermalservice 輸出解析 | 需實機確認 |
| A55 GPU load 節點是否可讀 | 需實機確認；無讀數時只使用 750 MHz 基準層 |
| 長時間 60 FPS／溫度 | 尚未實測 |

**不會把 CI 編譯成功當成實機驗收成功。**


## 0.8.0 GPU 遙測監視器

0.8.0 新增獨立的唯讀 GPU 遙測取樣器，與原本負責前景偵測、熱狀態判斷及頻率調整的控制排程分開執行。

- 遙測使用獨立單執行緒 WuWa-GPU-Telemetry，每次取樣完成後才開始下一次，不會因取樣變慢而累積任務。
- 控制流程與 UI 讀取同一份原子快照；GPU 負載不再由控制流程另開一套讀取迴圈。
- 目前每秒讀取 cur_freq、min_freq、max_freq、governor、available_frequencies 及既有候選 GPU 負載節點。
- 快照保留成功讀取的節點路徑、原始值、正規化值、單位、取樣時間和錯誤狀態。
- UI 將實際時脈 cur_freq 與最低頻率設定 min_freq 分開顯示，並顯示負載來源、原始值和資料新鮮度。
- 過期或不可解析的負載不會轉成 0%；控制器只使用新鮮且通過既有 parser 的負載數據。

### 0.8.0 尚未完成的交叉核對

以下項目仍是後續工作，不應視為已實作：

- 尚未依每種 GPU 節點的核心定義建立來源專用 parser。
- 尚未實作累積 busy/total 計數器的差值算法、重置偵測及異常差值拒絕。
- 尚未建立一般 App 與 Shizuku 對同一來源的權限通道比對。
- 尚未建立完整的 Reading<T>／CrossValidator 資料可信度模型。
- 尚未取得可驗證鳴潮遊戲 layer 的 present-to-present FPS。
- 尚未加入完整 CPU、RAM、溫度與長時間效能紀錄；目前 RAM 和 thermal 仍只作為既有控制保護訊號。

### 已知安全審查項目

0.7 原有的還原流程會在目前 min_freq 不等於備份原值時寫回原值，尚未記錄並確認目前值仍等於本 App 最後套用的值。因此外部程式或使用者在加速期間修改 min_freq 時，存在被還原流程覆蓋的風險。此項尚未修正，必須完成條件式還原設計及測試後，才能視為符合專案安全原則。0.8.0 不應被解讀為已解決此問題。

GPU 遙測線程與控制排程分離，只能避免 GPU sysfs 取樣卡住控制快照讀取及 UI；它不代表現有 dumpsys 控制流程已完全解除鎖定阻塞，也不能保證 sysfs I/O 本身一定會在固定時間內返回。

