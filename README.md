# WuWa Smooth Accelerator 1.0.3

## 幀時間與漏幀率驗收（外部 ADB 工具）

本專案新增 tools/frame_pacing/ 作為開發驗收工具，不加入 App HUD 或常駐監控。工具使用 Android 官方 SurfaceFlinger TimeStats 擷取指定鳴潮 Layer，保存原始輸出並產生 JSON 摘要。它只讀取呈現統計，不修改 Game Mode、sysfs、CPU／GPU 設定或背景程序。使用方式與限制見 tools/frame_pacing/README.md。

量化驗收目標（60 FPS 測試設定）：
- 單幀目標預算：16.67 ms。
- SurfaceFlinger 鳴潮 Layer 的 droppedFrames / (totalFrames + droppedFrames)：不高於 1.0%；若系統未提供欄位或無法識別正確 Layer，結果必須標示 N/A，不得當成 0%。
- 同時比較 presentToPresent P50／P90／P95／P99 與超過 25 ms（60 FPS 預算的 1.5 倍）的長間隔比例，以觀察尾端尖峰。長間隔比例是診斷指標，不等同 droppedFrames。
- 每組基準／候選設定使用相同遊戲版本、畫質、測試路線、時長及盡可能相近的溫度起點；分開測試，不同時改 Game Mode 與 INI。建議每組至少三次配對測試，持續 15–30 分鐘。

SurfaceFlinger 數據只代表系統呈現端，不等於 UE4 內部 Game Thread、Render Thread、Shader 或 GPU 執行時間。工具回報達標也不代表所有引擎內部卡頓已消失；只有完成 A55 實測，才能判定實際改善。

**目標只有兩件事：畫面銜接順暢、減少遊戲卡頓。**

本專案針對 Samsung Galaxy A55 5G（SM-A5560 / Exynos 1480 / Xclipse 530）與鳴潮 Android 版本開發。開發前必須閱讀 [DEVELOPMENT_RULES.md](DEVELOPMENT_RULES.md)。

## 設計方向

- 移除懸浮 HUD、CPU 使用率／頻率監控、GPU 溫度顯示及 SurfaceFlinger FPS／1% Low 診斷介面。
- App 只保留「啟動順滑加速」與「停止並還原」兩個操作。
- App 使用 Android GameManagerService 已提供給鳴潮的 Performance mode；另在 OEM profile 未被自訂、原始 game_overlay 為 null 時，嘗試套用 60 FPS throttling 以改善未 pacing 遊戲的呈現規律性。
- 切換前持久化保存原 Game Mode，切換後重新查詢確認；停止服務時還原原模式並再次讀回驗證。還原失敗時保留備份。
- 不寫入 Game Mode downscale；只在 OEM profile 明確為 scaling=1.0、Use Angle=false、Fps 空白且 game_overlay 原始值為 null 時，套用可回復的 Performance mode 60 FPS intervention。既有自訂設定一律不覆寫。不修改 GPU／CPU sysfs、governor、cpuset、uclamp、scheduler、電壓或 thermal 節點。
- SGPU `min_freq` 在目標韌體上是 root:root 0644，Shell UID 2000 無寫入權限；舊 GPU 控制路徑不再作為啟動加速的主路徑。

## 效能驗收目標

本專案不以平均 FPS 單一數字作為成功標準。主要驗收：

1. 角色切換、快速移動、轉向、跳躍／滑翔、技能與大招時，畫面銜接是否更穩定。
2. 幀時間尖峰、連續掉幀與可感知卡頓是否減少。
3. 連續遊玩 15–30 分鐘後，是否因熱累積而出現明顯退化。

平均 FPS、1% Low、呈現間隔與溫度可以在開發驗收時透過外部 ADB／系統工具取得，但不再放進加速器 App 內常駐監控。任何改善都必須有相同場景、相同畫質、相近溫度起點的修改前後實測；CI 通過不代表實機效能已改善。

## 系統限制

- 僅在 GameManagerService 回報鳴潮支援 Performance mode 時才切換；60 FPS intervention 僅在確認 OEM 原始 profile 未被覆寫時啟用，並在停止或遊戲離開前景時 reset 回 OEM 原始設定。
- Android Game Mode 是 OEM／系統提供的 per-game 策略入口；模式切換成功不代表 Samsung 韌體一定會提高實際效能。
- 不會關閉 Android 熱保護、不會強制超頻，也不會殺除其他 App。
- 外部工具無法保證修復遊戲引擎內部 Shader 編譯、資源載入或遊戲自身邏輯造成的卡頓。
- 不保證零卡頓或固定 60 FPS；必須以實機幀時間結果判定。

## 外部專案與技術研究（2026-11-10）

本節記錄外部 Android 遊戲效能專案的實際適用性。**「值得研究」不等於「可直接安裝／移植」；README 的功能宣稱也不等於已在 SM-A5560 驗證。** 評估前提是本專案的兩個目標：畫面銜接順暢、減少幀時間尖峰與卡頓。

### 已檢視專案

| 專案 | 值得研究的部分 | 不可直接採用的原因 | 本專案決策 |
|---|---|---|---|
| [Uperf-Game-Turbo](https://github.com/yinwanxi/Uperf-Game-Turbo) | 事件驅動 Hint、HeavyLoad、SfLag／SfBoost、渲染開始／結束訊號、避免持續重負載時無限制升頻 | README 列出的 Android 支援範圍為 Android 6–12；安裝路徑以 Magisk 或已 Root 手動安裝為主；SfAnalysis 需要對 SurfaceFlinger 做 native 注入，OEM／Android 版本／SELinux 相容性風險高；未找到 Exynos 1480／SM-A5560 實測支援 | **只借鑑事件驅動控制概念**。不移植注入器、核心節點設定檔或 CPU 親和性寫入 |
| [P.U.L.S.E.](https://github.com/keiretrogaming/pulse) | AutoTDP 閉環控制、目標幀率可達性判斷、CPU／GPU 控制協調、依負載與熱狀態調節而非固定拉滿 | 無 Root 控制依賴裝置內建的 `PServerBinder`；官方列出的實測裝置是 AYN／Retroid 掌機，沒有 Samsung A55；GPU 控制使用 Adreno power-level 機制，不可套用 Xclipse 530 | **借鑑閉環控制與「目標不可達時停止追逐」原則**。不移植 PServer、Adreno 節點或頻率表 |
| [ApexTuner](https://github.com/skysyaz/apextuner) | Shell 能力分層、裝置節點探測、寫入前備份、交易式寫入、讀回與 rollback 的架構 | CPU／GPU sysfs 寫入明確需要 Root；文件列出的 Samsung Exynos 驗證型號為 2200／2400，並非 1480。檢視到的控制器在 read-back 不可讀時會回傳成功，這不足以滿足本專案嚴格的寫入驗證要求 | **只借鑑交易式備份／回滾架構**；不可照搬其權限判定、SoC 路徑或控制器實作 |
| [FrameX-Android](https://github.com/MaheshSharan/FrameX-Android) | 以 SurfaceFlinger `--timestats` 取得呈現資料、讀取 ThermalService、Shizuku 權限邊界與診斷流程 | 主要是監控／HUD 工具，並包含背景程序管理、RAM heap compaction 等非本專案核心功能；SurfaceFlinger 呈現資料不是 UE4 內部 GPU render time；其「zero overhead」屬專案宣稱，未在 A55 獨立驗證 | **只借鑑外部驗收方法**，不增加常駐 HUD、RAM 清理或背景程序終止 |
| [Project Zenith](https://github.com/Clide01/ProjectZenith) | Shizuku 操作能力回報、原生 Game Mode、指定 App 的 ART `speed-profile` 編譯、操作還原 | 通用系統工具；ART 編譯主要影響 DEX，不會直接編譯鳴潮 UE4 原生程式、Shader 或 PSO；動畫縮放主要影響 Android 系統 UI，不等於遊戲畫面幀率 | **Game Mode 與指定 App 編譯僅列為獨立實驗候選**，先驗證 One UI 及鳴潮的實際支援；不加入常駐加速流程 |
| [HyperBoost Engine Pro](https://github.com/A-124/hyperboost-engine-pro) | Shizuku 能力回報、每遊戲設定、設定快照／還原、操作失敗回報 | 功能範圍廣，包含刷新率、渲染設定、背景控制與 HUD；README 宣稱不代表每個設定都在 A55 可用，具體命令與實機效果尚未逐項驗證 | **保留作為後續原始碼審查候選**；目前不移植未經驗證的設定 |
| [GameCore](https://github.com/Dreamucxe/GameCore) | `Observed<T>` 明確表達讀值來源、精度與不可用原因；Profile 將 null 定義為不修改，並恢復修改前讀值 | 偏向裝置管理與資料呈現，不是針對 UE4／GPU 幀時間的直接加速器 | **採納資料可信度與還原語義的設計原則**，不引入其整套應用架構 |
| [Game-Booster-v1](https://github.com/IrrelevantOnGit/Game-Booster-v1) | 可參考其 Kotlin App 分層與測試目錄 | README 自述解析度／FPS 滑桿在多數裝置上只是顯示值，AI 模型位置仍是空的；清理快取、終止背景程序沒有證據能改善鳴潮原生渲染卡頓 | **不採用效能調校做法** |
| [ADB-Android-Optimizer](https://github.com/SchneeSchmitt/ADB-Android-Optimizer) | 可作為 ADB 指令分類與 Android ART 編譯方式的研究材料 | 全域 ART compile 可能增加耗時、發熱與儲存占用；HWUI／RenderEngine Vulkan 設定不會自動切換 UE4 Vulkan；熱狀態覆寫及清除 Samsung GOS 資料不適合本專案 | **不整包執行**；ART 只允許獨立、可回復、指定鳴潮套件的實驗 |
| [Android 記憶體優化文章](https://github.com/jeanboydev/Android-ReadTheFuckingSourceCode/blob/master/article/android/basic/Android-%E6%80%A7%E8%83%BD%E4%BC%98%E5%8C%96-%E5%86%85%E5%AD%98%E4%BC%98%E5%8C%96.md) | 記憶體生命週期、避免不必要配置、先分析再調整的觀念 | 文章主要是 Android App／Java 記憶體管理；鳴潮還涉及 UE4 Native Memory、貼圖串流與 GPU 資源，不能用 Bitmap／GC 技巧直接解決 | **只保留記憶體壓力分析觀念**；不做強制 GC、RAM 清理或背景程序清除 |

| [WuWa Config Patcher](https://github.com/Arglax/WuWa-Mobile-Config-Patcher) | 針對鳴潮的 Shizuku INI 備份／套用／還原、CVar Analyzer、GC CVar 預存檢查；Game Resizer 使用 Android per-app Game Mode backbuffer downscale | v2.1.1 README 標記為 final build、不再更新；其 Game Resizer 文件描述 Android 14+ 使用 `cmd game set --downscale`，Android 13 使用不同 `game_overlay` 流程。尚未在 SM-A5560 驗證 Samsung GameManagerService、鳴潮是否允許 intervention；Kotlin 實作尚未逐行審查，根目錄 LICENSE 未找到 | **不採用 downscale**：SM-A5560 實測 0.9 downscale 會縮小遊戲介面並造成按鈕難以操作；已停止該實驗。只借鑑 CVar 分析／GC guard 概念，不直接複製程式碼 |
| [Mobile WuWa-Config](https://github.com/Arglax/Mobile-WuWa-Config) | 鳴潮 3.7 的 Engine.ini／DeviceProfiles.ini 設定、裝置 profile 與 CVar 參考資料；可用來比對已知配置風險 | README 說明配置主要測試於 Dimensity 8300；相容性表列 Exynos 1380／2400，沒有 Exynos 1480／Xclipse 530 的 A55 實測。其 `forbidden_cvars.txt` 標示 V3.6，不能當成 3.7 的最新黑名單 | **只作 CVar 候選與結構參考**；不整份套用其他 SoC 設定，所有變更需以 3.7 Client.log 驗證 |
| [Geilan63lan/Wuwa-Mobile-Config](https://github.com/Geilan63lan/Wuwa-Mobile-Config) | 個人整理的 3.7 All Devices／Snapdragon 分級設定，可供比對各品質層級差異 | 作者明確標記兩份配置是依相鄰層級推測重建、未驗證；All Devices 代表廣泛 GPU 類別，不代表 A55 專屬調校；沒有 SM-A5560 實測 | **不直接採用預設檔**；僅作差異比較，特別留意作者標記的未驗證值 |
| [WuWa Tool Android](https://github.com/k4wai1/WuWa-Tool-Android) | Shizuku 管理鳴潮 INI、首次備份、設定檔套用與還原流程 | 作者標示目前 unmaintained，並推薦改用其他 patcher；主要是設定檔管理，不是即時 CPU／GPU 控制器 | **不採用**；備份／還原概念已由本專案既有安全規範涵蓋 |
| [wuwa-tweaks](https://github.com/yazninja/wuwa-tweaks) | PC 版遊戲檔案備份與 Shader／Texture／Mesh 調整思路 | 專案提供 Windows／Linux／macOS 執行檔，README 沒有 Android 支援；PC 資產與 Android UE4 Vulkan 渲染路徑不可混用 | **不採用於 Android**；不移植 PC 資產修改或 FPS unlock 做法 |
| [WuWaLab](https://github.com/Arglax/WuWaLab) | 無 | 官方 README 明確說明它不讀取遊戲檔案、不自動化遊戲、不連接遊戲伺服器，是離線伴侶工具 | **與效能目標無關** |

| [RADV-Xclipse](https://github.com/JimVulkan/radv-xclipse) | Xclipse 530 專用 Mesa RADV Vulkan driver、driver 內 CPU／GPU frame-time profiling 開關、render-target compression 與 texture format 路徑 | README 明確說明套件供能從 ZIP 載入自訂 Vulkan driver 的 app（例如 Eden／Winlator）；不是替一般已安裝原生遊戲直接換 driver。A55 SM-A556B 曾有測試，但不能推定 SM-A5560／鳴潮相容；文件亦列出 GPU hang、重啟及 shader cache 相容風險 | **不安裝、不注入鳴潮**。只作為 Xclipse 530 GPU 架構與 profiling 概念參考 |
| [Surface FPS Monitor](https://github.com/anlaki-py/fps-monitor) | Shizuku 讀 SurfaceFlinger TimeStats、500 ms 取樣、SurfaceView／BLAST layer 選擇與明確顯示 Idle／N/A | 專案作者的實測環境是 Xiaomi 12T／Android 15／HyperOS 2；Samsung A55 相容性未確認，且工具本身是懸浮 FPS 監控 | **只列為臨時外部驗收候選**；先用 ADB 原生 TimeStats 命令驗證 A55，不把 HUD 加回 WuWaAccelerator |
| [GAMA](https://github.com/palincat/gama) | Shizuku 操作固定系統屬性、切換設定後還原、Samsung One UI 相容性回報 | 原始 README 明確使用 `setprop debug.hwui.renderer`，控制的是 Android HWUI renderer，不是鳴潮 UE4 Vulkan renderer；切換可能導致 System UI soft-crash | **不採用**；不能把 HWUI Vulkan 當成遊戲 Vulkan 切換 |
| [Moha Lab Optimization](https://github.com/7Mohan/mohalaboptimazation) | README 提及 per-game Game Mode、resolution downscale、FPS override、ART 編譯與還原原值 | 專案 LICENSE 明確標示 view-only，禁止複製、修改、建置或再散布；Root 模式與 Shizuku 能力不同，具體命令及 A55 實測未獨立核實 | **不複製程式碼、不安裝未驗證 tweak**；只把公開功能描述視為待驗證研究線索 |
| [EliteGaming](https://github.com/DivyanshNagda/Elitegaming) | Shizuku 控制顯示解析度／DPI，可能作為降低像素填充率的實驗線索 | Repository 只有單一 commit；README 宣稱「最高 +80% FPS」但沒有可重現的 A55 數據，另含 aggressive LMK 與預先殺 App | **不採用其 boost 宣稱或清理功能**；解析度只能作獨立、可還原的畫質／GPU 負載 A/B 實驗，不當成直接加速方案 |

### 可轉化為本專案設計的原則

1. **事件優先於盲目輪詢（研究方向）**：研究前景切換、GPU 負載突增、渲染提交延遲等事件是否能提供比固定頻率輪詢更及時的控制訊號。任何事件來源都必須驗證在 One UI／Android 17／鳴潮遊戲 Layer 上真的可用。
2. **閉環控制而非固定拉滿**：將負載、實際 GPU 頻率、熱狀態與呈現間隔視為不同訊號；先判斷資料可信度，再決定是否調整。目標不可達或熱餘裕不足時，不應持續追逐高頻。
3. **交易式寫入與安全回復**：寫入前保存原始值；寫入後必須讀回確認；驗證失敗則回滾。讀回不可用、來源不明或權限不足時，不可宣稱成功。
4. **明確表示未知資料**：不可把讀取失敗、過期資料或不支援狀態轉成 0；控制器應 fail-safe。
5. **記憶體壓力以證據判斷**：只有在 A/B 測試發現記憶體回收、zRAM／swap 或資源載入與幀時間尖峰有關時，才考慮對應策略；禁止把「空閒 RAM 越多越好」當成效能定律。
6. **短時尖峰與長時熱穩態分開測試**：技能／角色切換等短時尖峰與 15–30 分鐘熱穩態是不同問題，必須分別量測。
7. **外部量測不等於遊戲內部計時**：SurfaceFlinger／FrameTimeline 可提供呈現端證據，但不一定能辨識 UE4 的 CPU game thread、render thread、GPU 執行時間或 Shader 編譯原因。

### 官方技術依據與限制

- [Android 遊戲效能最佳化總覽](https://developer.android.com/games/optimize/overview)：AGI、Perfetto、ADPF、Game Mode、Memory Advice 等工具的定位。
- [Android Dynamic Performance Framework（ADPF）](https://developer.android.com/games/optimize/adpf)：Thermal API、Performance Hint、Game Mode／Game State 是遊戲與系統互動的正式機制；通常需要遊戲本身整合，外部加速器不能假裝替鳴潮呼叫其內部工作階段。
- [Game Mode interventions](https://developer.android.com/games/optimize/adpf/gamemode/gamemode-interventions) 與 [FPS throttling](https://developer.android.com/games/optimize/adpf/gamemode/fps-throttling)：Android 官方指出 FPS throttling 可讓未自行 pacing 的遊戲以更穩定幀率執行；本專案只測試 Performance mode 60 FPS，不使用 downscale。設定前檢查 `device_config game_overlay` 與 OEM profile，設定後讀回，停止時以 `cmd game reset --mode 2` 還原 OEM 預設。
- [Android Slow Sessions](https://developer.android.com/games/optimize/vitals/slow-session)：慢影格可能來自 CPU、GPU、熱降頻或刷新率／幀率不匹配；不能只看 GPU utilization。
- [Android Frame Pacing／Swappy](https://developer.android.com/games/sdk/frame-pacing)：UE 4.25+ 可整合 Android Frame Pacing，但是否啟用及其遊戲內部設定需要遊戲端支援；外部 App 無法直接把 Swappy 注入鳴潮。
- [Perfetto FrameTimeline](https://github.com/google/perfetto/blob/main/docs/data-sources/frametimeline.md)：Android 12+ 可分析 jank 與預期／實際時間軸；文件目前註明 SurfaceView 尚不支援，因此必須先確認鳴潮實際 layer 類型，不能預設資料完整。

### 採用分級

- **已實作、待實機驗證**：只切換鳴潮已提供的 Android Performance mode；保存 standard 原模式、切換後讀回確認，停止時還原並讀回。此模式是否對 Samsung A55 的實際遊戲效能有幫助尚未證明。
- **已排除**：Game Mode downscale 0.9 在 SM-A5560 造成遊戲 UI 縮小、操作區域不合用；不再啟用。
- **可採納的設計原則**：可信度／新鮮度檢查、事件狀態機概念、交易式寫入與回滾、控制器遲滯、外部 A/B 驗收。
- **需 A55 實測後才能採用**：Performance mode 效果、SurfaceFlinger／FrameTimeline 訊號、指定套件 ART 編譯。
- **不採用**：需要 Root／Magisk／PServer 的控制路徑、未驗證 CPU 核心綁定或 cpuset／uclamp／scheduler 寫入、Adreno 專用 GPU 節點、SurfaceFlinger 注入、關閉熱保護、清除 Samsung GOS 資料、強制清 RAM／殺背景程序。
- **目前不新增功能**：這份研究紀錄只提供未來設計參考，不代表上述功能已寫入 App 或已在 A55 證明有效。
- **額外排除項目**：RADV-Xclipse 只供能載入自訂 driver ZIP 的容器 App；GAMA 的 `debug.hwui.renderer` 只控制 HWUI；Surface FPS Monitor 只作外部驗收候選；Moha Lab 為 view-only 授權；EliteGaming 的高 FPS 增益宣稱缺少可重現證據；WuWa 手機設定檔未有 A55 專屬實測，不可直接套用。





### 2026-11-10 Performance FPS intervention

- Android 官方 FPS throttling 說明指出，平台 FPS intervention 可改善未自行 frame pacing 遊戲的穩定呈現；遊戲若已自行 pacing，介入可能無效。
- 本專案只考慮 60 FPS，不套用 downscale。只在 device_config game_overlay 原始值為 null，且 cmd game list-configs 顯示 Performance mode scaling=1.0、Use Angle=false、Fps 空白時，才套用 cmd game set --mode 2 --fps 60。
- 寫入前保存並校驗 OEM 預設快照；寫入後必須讀回 Fps=60 且 scaling/angle 不變。停止、遊戲離開前景或錯誤時執行 cmd game reset --mode 2，讀回必須與原快照相同；外部修改時拒絕覆蓋並保留備份。
- AOSP 命令語義依 Android GameManagerShellCommand；實際 One UI 9 行為、鳴潮是否屬未 pacing 遊戲及幀時間收益仍待 SM-A5560 實測。不得把 CI 通過當作實機有效。

## 2026-11-10 實作狀態

- A55 回報鳴潮可用 Game Mode 為 standard/performance（mode IDs 1、2）；Performance mode 可由 `cmd game mode performance <package>` 切換並由 `cmd game list-modes <package>` 讀回。
- App 現在只使用上述既有 Performance mode，不設定 downscale 或 FPS intervention；原始模式持久化保存，停止時還原並讀回。
- A55 的 SGPU `min_freq` 為 root:root 0644，Shell UID 2000 不可寫；因此不再把 GPU sysfs 寫入當成可用加速路徑。
- Android 官方說明 FPS throttling 可改善未自行 pacing 遊戲的呈現穩定性；此 60 FPS intervention 已加入安全條件與 rollback，但鳴潮是否屬未 pacing 遊戲、Samsung 韌體是否實際執行及 A55 幀時間收益仍待實機 A/B，不得預先宣稱改善。

## 建置與測試

本機完整驗證：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

CI 會執行單元測試、Android Lint、Debug／Release APK 建置。請以最新 commit 對應的 GitHub Actions 結果為準，不可用舊版本綠燈代替。

## A55 實機驗收

1. 確認 Shizuku 已啟動並授予權限。
2. 確認 App 能啟動 UserService，且鳴潮 Game Mode 由 standard 切換至 performance 後讀回一致；若原本已是 performance，App 不得宣稱自己造成了切換。
3. 依固定路線測試角色切換、摩托車高速移動與轉向、跳躍／滑翔、第一個技能及大招。
4. 以外部 ADB／系統工具記錄呈現間隔、GPU 頻率、熱狀態與掉幀；不把單張截圖當成持續效能證據。
5. 連續測試至少 15–30 分鐘，停止加速後確認原 Game Mode 已安全還原。

**實機驗收尚未完成前，不得宣稱卡頓已解決。**
