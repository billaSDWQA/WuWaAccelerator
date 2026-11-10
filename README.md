# WuWa Smooth Accelerator

**目標只有兩件事：畫面銜接順暢、減少遊戲卡頓。**

本專案針對 Samsung Galaxy A55 5G（SM-A5560 / Exynos 1480 / Xclipse 530）與鳴潮 Android 版本開發。開發前必須閱讀 [DEVELOPMENT_RULES.md](DEVELOPMENT_RULES.md)。

## 設計方向

- 移除懸浮 HUD、CPU 使用率／頻率監控、GPU 溫度顯示及 SurfaceFlinger FPS／1% Low 診斷介面。
- App 只保留「啟動順滑加速」與「停止並還原」兩個操作。
- 保留控制器內部最小必要的 GPU 負載取樣。這不是給使用者看的監控功能，而是判斷 GPU 最低頻率請求的控制輸入；沒有可信負載資料時必須 fail-safe，不可盲目升頻。
- 保留前景偵測、Android ThermalService 熱保護、記憶體壓力保護、GPU 頻率寫入讀回驗證、原始值備份與安全還原。
- 不對 CPU governor、cpuset、uclamp、scheduler、電壓或 thermal 節點寫入。Shizuku shell UID 2000 不等於 Root，不能假設可控制 Android／Samsung 核心排程。
- GPU `min_freq` 只是最低頻率請求，不等於實際 GPU clock；核心 governor 和熱管理仍掌握實際頻率。

## 效能驗收目標

本專案不以平均 FPS 單一數字作為成功標準。主要驗收：

1. 角色切換、快速移動、轉向、跳躍／滑翔、技能與大招時，畫面銜接是否更穩定。
2. 幀時間尖峰、連續掉幀與可感知卡頓是否減少。
3. 連續遊玩 15–30 分鐘後，是否因熱累積而出現明顯退化。

平均 FPS、1% Low、呈現間隔與溫度可以在開發驗收時透過外部 ADB／系統工具取得，但不再放進加速器 App 內常駐監控。任何改善都必須有相同場景、相同畫質、相近溫度起點的修改前後實測；CI 通過不代表實機效能已改善。

## 系統限制

- 僅允許已驗證的 SM-A5560 裝置使用固定 SGPU sysfs 控制路徑。
- 不需要 Root，但節點寫入是否成功取決於韌體權限；讀取成功不代表可寫入。
- 不會關閉 Android 熱保護、不會強制超頻，也不會殺除其他 App。
- 外部工具無法保證修復遊戲引擎內部 Shader 編譯、資源載入或遊戲自身邏輯造成的卡頓。
- 不保證零卡頓或固定 60 FPS；必須以實機幀時間結果判定。

## 建置與測試

本機完整驗證：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

CI 會執行單元測試、Android Lint、Debug 與 Release APK 建置。請以最新 commit 對應的 GitHub Actions 結果為準，不可用舊版本綠燈代替。

## A55 實機驗收

1. 確認 Shizuku 已啟動並授予權限。
2. 確認 App 能啟動 UserService，且 SGPU 節點讀取／寫入權限符合預期。
3. 依固定路線測試角色切換、摩托車高速移動與轉向、跳躍／滑翔、第一個技能及大招。
4. 以外部 ADB／系統工具記錄呈現間隔、GPU 頻率、熱狀態與掉幀；不把單張截圖當成持續效能證據。
5. 連續測試至少 15–30 分鐘，並在停止加速後確認原始 GPU 設定已安全還原。

**實機驗收尚未完成前，不得宣稱卡頓已解決。**
