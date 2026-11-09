# 鳴潮 GPU 自動加速器（WuWaAccelerator）

這是一個針對 **Samsung Galaxy A55 5G（SM-A5560／Exynos 1480／Xclipse 530）** 的 Shizuku 工具。它只嘗試調整一個 GPU devfreq 節點，不修改 CPU、記憶體、網路、螢幕刷新率或遊戲檔案。

> **目前版本：0.9.0 測試版。** 本版將 Galaxy A55 已實測的 SGPU `current_utilization` 讀值接入共用 GPU 遙測快照，並以 `utilization_source=jiffies` 作為自動調頻的必要條件。CI 通過只代表程式可編譯與單元測試通過；Shizuku 實機讀取、懸浮 HUD、頻率控制效果與遊戲幀率仍須另外驗證。

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

備份位置：`/data/local/tmp/WuWaAccelerator/gpu-state-v2`（由 Shizuku UserService 的 shell 身分管理）；舊版 `gpu-state-v1` 僅供保守遷移檢查。

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

### 0.8.0 已完成的負載來源交叉檢查

- 取樣器會保留四個候選負載節點各自的來源、原始讀值、解析結果及錯誤狀態。
- 只比較通過既有 parser 且單位為 0–100% 的讀值；多個有效來源差異超過 15 個百分點時，該次 GPU 負載標示不可用，控制器不會用它提高最低頻率。
- 節點缺失或格式不明不會被轉成 0%。busy/total 累積計數器仍未納入，因為各節點必須先確認核心介面定義及計數器單位，才能正確計算差值。
- 尚未建立一般 App 與 Shizuku 對同一來源的權限通道比對，也尚未取得可驗證鳴潮遊戲 layer 的 present-to-present FPS。
- 尚未加入完整 CPU、RAM、溫度與長時間效能紀錄；目前 RAM 和 thermal 仍作為既有控制保護訊號。

### 0.8.0 條件式還原與舊備份遷移

- 新 v2 備份記錄原始值、最後套用值及更新前的已知值；每次修改前先同步更新還原紀錄，讓程序中斷時能辨識轉換前後兩個狀態。
- 還原只在目前 min_freq 等於原始值、最後套用值或紀錄中的前一個值時執行。若目前值與紀錄不符，程式保留備份並拒絕覆寫外部修改。
- 0.7.0 的 v1 備份沒有最後套用值，因此無法證明目前不同於原始值的設定仍由本程式控制。若目前值已等於備份原始值，可以安全清除舊備份；否則保留並顯示原因，不盲目寫回。

### 執行緒與效能量測邊界

前景偵測與 thermalservice 的 dumpsys 命令已移出 GPU 控制鎖；若 shell 命令正在等待逾時，手動停止與還原仍可取得控制鎖。GPU sysfs 讀寫和少量 /proc/meminfo 讀取仍在控制流程中，不能保證核心節點 I/O 一定有固定返回時間。

FPS 尚未接入。未確認鳴潮正確 layer 之前，不以螢幕刷新率、App 自身 Choreographer 或 GPU 負載估算遊戲 FPS。



## 0.9.0 A55 SGPU 負載來源整合

- 新增主要負載節點：`/sys/class/devfreq/10e00000.sgpu/interface/current_utilization`。此節點在 Galaxy A55 SM-A5560 上已透過 ADB 實測可讀，遊戲運作樣本為 62–86%，手機主畫面閒置樣本為 0%。這些是有限次手動取樣，不是完整效能基準測試。
- 同步讀取 `interface/utilization_source` 與 `interface/available_utilization_sources`。目前裝置回報的模式為 `jiffies`；程式只在模式確認為 `jiffies` 時，才把 `current_utilization` 傳給自適應控制器。若模式缺失或不同，資料顯示為不可用，不猜測其語義，也不自動切換驅動模式。
- 新節點優先於原有四個候選負載來源；原有來源仍保留交叉檢查。若多個有效來源差異超過 15 個百分點，該次樣本仍會拒絕交給自適應升頻。
- `current_cu_utilization` 不作為整體 GPU 使用率，因為目前 A55 實測為 0，且不能代表整顆 GPU 的工作負載。
- `cur_freq=0` 會顯示為「閒置或驅動未回報」，不再呈現成 0 MHz 的有效運作時脈；負載 0% 仍是有效讀值。
- UI 與自適應控制器仍共用同一份每秒遙測快照，沒有新增第二套 GPU 取樣器，也不寫入 utilization source 設定。
- 將 Shizuku UserService 版本標記由 7 升至 8，避免舊 daemon 繼續使用更新前的遙測類別。

### 0.9.0 尚未完成的項目

- Shizuku UserService 在 SM-A5560 上讀取新節點，並與 ADB 同時讀值比對。
- 遊戲內懸浮 HUD（需另行加入 overlay 權限、前景服務及生命週期管理；本次核心遙測提交不會假裝已完成）。
- 較短取樣週期與短暫掉幀尖峰的關聯測試。
- 實際 FPS、長時間溫度、穩定性和 60 FPS 驗證。

