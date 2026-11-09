# WuWaAccelerator 1.0.0

**鳴潮專屬加速器｜Samsung Galaxy A55 5G（SM-A5560 / Exynos 1480 / Xclipse 530）**

本分支以 1.0.0 為完整整合版本，保留 0.9.0 的 SGPU 自適應最低頻率控制，加入 CPU 唯讀監測、SurfaceFlinger 呈現統計診斷，以及完整的前景生命週期清理。

> **驗收原則：CI 通過不等於 A55 實機驗收通過。** 本分支仍須在 SM-A5560、Android 17 / One UI 9、Shizuku shell UID 2000 環境實測；尤其要確認 SurfaceFlinger 是否公開鳴潮 layer、SGPU 寫入權限、HUD 長時間運作與遊戲 15–30 分鐘以上的溫度／幀時間表現。

## 1. 功能

### GPU 自適應控制
- 只針對已驗證型號 Samsung SM-A5560。
- 透過 Shizuku UserService 讀取 A55 SGPU devfreq 節點。
- 在驅動回報的負載來源確認為 `jiffies` 且資料新鮮時，才把 SGPU 利用率交給控制器。
- 依負載與保護狀態提出 750 / 864 / 960 / 1025 MHz 的 `min_freq` 下限請求；會先核對硬體上限及可用頻率步進。
- `min_freq` 是最低頻率請求，不是 GPU 實際時脈。實際時脈仍由核心 governor、GPU 負載及 Android 熱管理共同決定。
- 升頻／降頻採遲滯確認，避免短暫波動造成頻率來回切換。

### CPU 唯讀監測
- 以 `/proc/stat` 的 per-core 累積計數差值計算 CPU 整體與各核心使用率。
- 以 `/sys/devices/system/cpu/cpufreq/policy*/` 動態探索 CPU 頻率政策、相關核心及可讀取的目前／最小／最大頻率。
- 不假設 A55 核心編號或 policy 拓樸固定。
- **不寫入** CPU governor、cpufreq、cpuset、uclamp、排程器或電壓節點。Shizuku shell 權限不等於 Root；本版本不會宣稱能直接控制 CPU 排程或強制大核常駐。

### 遊戲呈現統計診斷
- 偵測鳴潮在前景時，嘗試啟用 `dumpsys SurfaceFlinger --timestats`，約每 10 秒讀取一次統計。
- 若系統公開鳴潮 layer，顯示 layer 名稱、統計影格數、SurfaceFlinger droppedFrames、呈現間隔 P95/P99，以及呈現間隔超過 16.7 ms 的比例。
- 鳴潮離開前景、使用者停止加速或服務正常銷毀時，會嘗試關閉 timestats。
- 若 One UI／SurfaceFlinger 不支援或不公開鳴潮 layer，會明確顯示不可用，不猜測其他 layer 是遊戲。
- **這是 SurfaceFlinger 呈現節奏，不是遊戲引擎內部 GPU render time，也不能直接當作 1% Low FPS。** 它是定位呈現卡頓的輔助證據。

### 熱與記憶體保護
- 讀取 Android thermalservice 節流等級；中度或更高、溫度狀態未知時停止提高 GPU 下限並嘗試還原。
- 輕度熱狀態限制在基準層。
- 讀取 `/proc/meminfo` 的 MemAvailable；記憶體壓力升高時限制升頻，嚴重不足時停止加速。
- 不關閉 Android 熱保護、不改電壓、不殺除其他 App、不強制超頻。

### HUD 與還原
- 使用者從主畫面明確啟動懸浮 HUD；HUD 透過既有 Shizuku Binder 讀取快照，不另開 GPU sysfs 讀取或寫入通道。
- 主畫面與 HUD 顯示 GPU、CPU 及可取得的 SurfaceFlinger 統計。
- GPU 修改前先備份原始值，記錄韌體指紋與最後套用值；每次寫入後讀回驗證。
- 還原前檢查備份完整性及目前值是否仍符合本程式最後套用紀錄；外部已修改時拒絕盲目覆寫。
- 還原失敗保留備份，不以硬編碼值猜測。重新連線時先處理舊備份。

## 2. 系統需求與限制

- Samsung Galaxy A55 5G，型號 **SM-A5560**。
- Android 8.0 以上；開發驗證目標為 Android 17 / One UI 9。
- 已啟動 Shizuku，並授予本 App Shizuku 權限。
- 首次使用 HUD 需授予「顯示在其他應用程式上層」權限。
- 不需要 Root；但 SGPU 節點是否可寫取決於該韌體的核心權限。讀得到不代表一定寫得進去。
- 不會修改鳴潮 Engine.ini、遊戲資產或遊戲內畫質／FPS 設定。
- 不保證穩定 60 FPS，也不能修復遊戲自身的邏輯卡頓、shader 編譯或資源載入尖峰。外部工具能提供的是安全的系統層控制與診斷資料。

## 3. 建置與安裝

CI 會執行：
1. ` :app:testDebugUnitTest `
2. ` :app:lintDebug `
3. Debug 與 Release APK 建置
4. 上傳可直接測試的 Debug APK artifact

在 GitHub 開啟 **Actions → Android CI 1.0.0**，選擇最新且成功的執行紀錄，下載 `WuWaAccelerator-1.0.0-debug` artifact 並解壓縮安裝。

若新版 APK 無法覆蓋安裝舊版，先在舊版 App 內停止並確認 GPU 原始值已還原，再解除安裝舊版。GitHub Hosted Runner 的 Debug 簽章可能隨建置不同。

本機建置：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

## 4. 建議驗收流程

1. 確認 Shizuku 已啟動，App 顯示 UserService 已連線。
2. 在主畫面確認 GPU、CPU 讀值是否新鮮；若權限不足，應顯示不可用，不得把缺失資料當成 0。
3. 啟動 HUD，再開啟鳴潮；確認前景偵測、GPU 控制狀態與 CPU 讀值持續更新。
4. 確認 SurfaceFlinger 統計是否找到鳴潮 layer。若找不到，記錄為韌體能力限制，不可拿系統其他 layer 代替。
5. 測試角色切換、技能／大招、摩托車上下車、快速移動與傳送等容易出現 frame-time spike 的場景。
6. 遊戲連續運作至少 15–30 分鐘，觀察 GPU 實際時脈、熱狀態、CPU 使用率、呈現間隔與掉幀變化。
7. 離開鳴潮後確認 GPU 原始 `min_freq` 已還原；手動停止時也確認備份狀態。

## 5. 尚待實機驗證

| 項目 | 狀態 |
|---|---|
| CPU / GPU 遙測程式與單元測試 | CI 驗證中 |
| Android Lint、Debug / Release 建置 | CI 驗證中 |
| SM-A5560 Shizuku CPU sysfs 讀取權限 | 待實機確認 |
| SM-A5560 SurfaceFlinger 鳴潮 layer 可見性 | 待實機確認 |
| SurfaceFlinger droppedFrames 與呈現間隔對照遊戲卡頓 | 待實機確認 |
| SGPU 自適應寫入與還原 | 需在目前韌體重新驗收 |
| HUD 長時間穩定性 | 待實機確認 |
| 15–30 分鐘以上溫度與幀時間穩定性 | 待實機確認 |
| 穩定 60 FPS | 不作保證，須以實機數據判斷 |

**任何 CI 綠燈都不能取代實機驗收。** 若遙測來源、熱狀態或還原紀錄不可信，控制器應優先停止加速並保留還原資訊。
