# WuWaAccelerator 開發規範（強制）

> 本文件是每次開發工作的第一份必讀文件。任何程式碼修改、設定修改、CI 調整、版本發布或效能策略變更，都必須先閱讀本文件、README.md、相關程式碼註解與既有 Issue／PR／CI 紀錄，再開始工作。
>
> **不得以「先修眼前錯誤」為理由跳過全局檢查。不得把編譯成功、CI 綠燈或監控數字變化直接宣稱為實機加速成果。**

## 0. 每次開發前的強制讀取清單

每一個新的開發任務開始前，必須重新讀取當前分支的最新版本，不可只依賴舊對話、舊摘要或先前記憶：

1. 本文件 `DEVELOPMENT_RULES.md`。
2. `README.md`：專案目標、架構邊界、安全限制、實機驗收狀態。
3. 當前分支、HEAD commit、工作樹／未合併變更（若可取得）。
4. `.github/workflows/` 全部工作流程及其觸發條件。
5. 與本次任務相關的原始碼、測試、介面定義、資源與**程式碼內既有註解**。不可只讀準備修改的單一函式。
6. 相關 Issue、PR 描述、PR review／討論、最近提交紀錄。
7. 最近 CI 執行紀錄：Run、Job、Step、完整失敗日誌、測試結果、Artifacts。若有紅叉，先查明原因與影響範圍。
8. 專案中所有可找到的規範、備註、設計決策、TODO、已知限制及驗收紀錄。搜尋範圍至少包含檔名與內容中的 `RULE`、`RULES`、`NOTE`、`TODO`、`ADR`、`CONTRIBUTING`、`AGENT`、`REDIS` 等關鍵字；找不到時必須明確記錄「未找到」，不可假稱已讀取。
9. 檢查 GitHub branch protection／repository rulesets（若目前權限可讀），並確認分支策略與 PR 目標分支。

完成上述讀取後，先整理「全局現況、已知故障、依賴關係、風險、驗收標準」，再提出本次修改方案。若關鍵規範或備註無法取得，先標示阻塞點，不可自行猜測。

## 1. 全局分析原則

- 先理解完整資料流與控制生命週期，再修改局部程式。
- 至少追蹤：UI → Binder/AIDL → Shizuku UserService → 遙測 → 策略判斷 → 寫入驗證 → 還原流程；依任務需要擴展至前景偵測、熱保護、記憶體保護、HUD、SurfaceFlinger。
- 檢查修改對其他模組、背景生命週期、錯誤處理、權限、耗電、熱累積與資料新鮮度的連鎖影響。
- 不因單一指標推導瓶頸。例如 GPU 使用率低不等於 GPU 一定不是瓶頸；CPU 整體使用率高也不等於遊戲主執行緒飽和。必須交叉比對頻率、溫度、呈現間隔、場景和時間序列。
- 區分「觀測事實」「合理推論」「尚待驗證假設」。報告中不得混為一談。
- 先建立基準與可重現測試，再比較修改前後；沒有基準資料不得宣稱改善。

## 2. GitHub 紅叉與失敗處理（硬性規定）

任何紅叉、CI failure、測試失敗、Lint error、建置失敗或上傳失敗，都必須：

1. 找到對應的 commit SHA、workflow run ID、job ID、PR 與分支。
2. 打開失敗 Job 的完整日誌，定位第一個真正的錯誤（root cause），不能只看最後的 `BUILD FAILED`。
3. 區分根因與後續連鎖失敗；例如 Unit Test 失敗導致 Lint／APK build 被 skip，不能把被跳過的工作說成自身失敗。
4. 檢查是否為程式錯誤、測試預期過時、環境／依賴問題、權限問題、工作流程設定或外部服務問題。
5. 評估同一根因影響哪些模組、分支、PR、版本與既有功能。
6. 修正後增加或更新回歸測試；不得只刪除測試、放寬 assertion 或關閉檢查來消除紅叉。
7. 重新執行完整相關驗證，並讀取新的 Job 日誌確認真正通過。
8. 在工作紀錄中列出 run、job、失敗 step、根因、修正 commit 與重跑結果。

**禁止事項：**
- 未讀失敗日誌就繼續堆疊新提交。
- 只因某一次舊 run 成功，就宣稱最新 commit 通過。
- 把 skipped、cancelled、timed out 或 neutral 說成 passed。
- 未驗證就宣稱修復完成。
- 隱藏、刪除或繞過檢查以製造綠燈。

## 3. 修改前的計畫與範圍控制

每次修改前先記錄：
- 問題描述與可重現條件。
- 證據及來源（log、測試、裝置輸出、程式碼位置）。
- 根因或待驗證假設。
- 涉及模組、依賴關係及可能副作用。
- 最小必要修改方案與不修改的範圍。
- 測試計畫、回歸風險及成功／失敗判定條件。
- 是否需要使用者實機操作或提供資料。

不要同時混入無關重構、格式化、功能改動或版本升級。若發現額外問題，先記錄並排序，不要失去本次任務主線。

## 4. A55／Android 系統安全邊界

- 目標裝置為 Samsung Galaxy A55 5G（SM-A5560）、Exynos 1480、Xclipse 530；仍須以使用者當前韌體與實測為準。
- Shizuku shell UID 2000 不等於 Root。讀取成功不代表可寫入；每一個寫入節點都要做權限、寫入結果及讀回驗證。
- 不得宣稱 CPU 已被加速，除非有實際且經驗證的 CPU 控制實作。既有 CPU telemetry 是唯讀。
- 未經明確設計與驗證，不可修改 CPU governor、cpuset、uclamp、scheduler、電壓、thermal 節點或關閉 Android 熱保護。
- GPU 控制只可在已驗證的裝置與節點範圍內運作；不得硬編碼假設所有韌體節點相同。
- GPU `min_freq` 是最低頻率請求，不是實際 GPU clock。必須分開記錄請求值、寫入／讀回值、`cur_freq`、負載、溫度與熱狀態。
- 負載來源無效、過期、彼此衝突或無法驗證時，必須採 fail-safe；不可把未知資料當成 0 或有效負載。
- 任何修改都必須保留原始值、韌體／裝置適用性檢查、寫入讀回驗證、外部變更保護及可靠還原路徑。
- 不得為了追求高頻而繞過熱保護、忽略記憶體壓力或提高不合理的固定頻率下限。
- 不修改鳴潮遊戲資產或遊戲檔案，除非專案目標與使用者明確授權另有變更；不得宣稱可保證穩定 60 FPS。

## 5. 遙測與效能結論

- 每個數值都要註明來源、單位、取樣時間、新鮮度與限制。
- GPU utilization、CU utilization、GPU clock、GPU min/max frequency、CPU overall/per-core、CPU policy frequency、GPU temperature、Android thermal status、MemAvailable、SurfaceFlinger FPS／dropped frames 不可互相替代。
- SurfaceFlinger 呈現統計不是 Unreal Engine 內部 GPU render time；若遊戲 layer 不可見，必須顯示 N/A，不可用螢幕刷新率猜測。
- 單張截圖只能支持該時刻的觀測，不足以證明長時間瓶頸或效能改善。
- 比較必須盡量固定遊戲版本、場景、畫質、溫度起點、電量／充電狀態、背景程序與測試時長。
- 需要觀察短時間尖峰及至少 15–30 分鐘長時間熱穩態；記錄平均 FPS、1% Low、frame-time／呈現間隔分布、掉幀、頻率與溫度。
- 若缺乏可用資料，結論應為「尚不能判定」，並列出下一個最小驗證步驟。

## 5A. 幀時間與漏幀率量化驗收（強制）

- 60 FPS 的目標幀預算為 16.67 ms；平均 FPS 不可代替幀時間分布。
- A55 實機 A/B 測試必須盡可能選取鳴潮自身 SurfaceFlinger Layer，記錄 totalFrames、droppedFrames 及 presentToPresent histogram。
- 專案漏幀驗收目標為 droppedFrames / (totalFrames + droppedFrames) <= 1.0%。若系統未提供欄位、Layer 無法確認或資料不完整，必須標示 N/A／不可判定，不可當成 0% 或通過。
- 同時比較 P50／P90／P95／P99 呈現間隔，以及大於目標幀預算 1.5 倍的長間隔比例；這些是呈現端穩定性指標，不可冒稱 UE4 內部 GPU／CPU Frame Time。
- 建議基準與候選設定各至少三次配對測試，每次 15–30 分鐘；固定遊戲版本、畫質、路線、刷新率、電量／充電狀態與起始溫度。Game Mode、INI 或 GPU 控制策略一次只改一項。
- tools/frame_pacing/measure_timestats.py 是外部 ADB 驗收工具，不屬於 App 常駐監控；需保留 raw dump 與 JSON 報告。正確 Layer 未匹配時不得使用其他 Layer 或螢幕刷新率代替。
- SurfaceFlinger droppedFrames／呈現間隔不能完整定位 UE4 Game Thread、Render Thread、Shader compilation 或 GPU execution 的內部原因；根因仍需結合 Perfetto／AGI、遊戲日誌及場景 A/B 分析。

## 6. 測試與交付門檻

每次修改依影響範圍執行：
1. 相關單元測試與新增回歸測試。
2. `:app:testDebugUnitTest`。
3. `:app:lintDebug`。
4. `:app:assembleDebug`。
5. `:app:assembleRelease`。
6. 檢查 APK／artifact 是否存在、檔案大小、版本資訊與 SHA-256。
7. 檢查最新 commit 的 CI run，不得用舊 commit 的成功紀錄代替。
8. 涉及裝置控制時，列出尚未完成的 A55 實機驗收項目。

只有所有要求的 CI 工作都成功才可稱「CI 通過」。只有完成實機測試才可稱「實機驗收通過」。只有修改前後有可比較的實測證據，才可稱「效能改善」。

## 7. 分支、PR 與版本管理

- 開發前確認目前 branch、base branch、HEAD SHA、工作目標與 PR 關係。
- 不可將 PR 指向中間功能分支，除非這是刻意採用並已記錄的 stacked-PR 流程；一般整合 PR 應指向指定的整合／主分支。
- 不可自行合併、刪除分支、force push 或改寫歷史，除非使用者明確授權且已確認風險。
- PR 描述必須列出變更、測試結果、未完成驗收、已知風險與安全影響。
- 不可把 Draft PR 說成已可發布；未完成實機驗收的功能必須清楚標示。
- 版本號、versionCode、UserService 版本與 release artifact 必須一致並可追溯。

## 8. 註解、備註與決策紀錄

- 修改前必須閱讀受影響程式碼附近的註解、TODO、FIXME、設計備註與測試名稱；註解可能包含重要安全條件，不可略過。
- 若實作與註解不一致，先判定哪一方過時，再同步修正，不可保留誤導性說明。
- 重要架構決策、硬體限制、失敗根因、實機觀察與未解問題，應記錄在 README 或專門的開發紀錄中，附日期、commit／run ID 和證據。
- 未找到某份規則或備註時，必須說明搜尋範圍與未找到的結果，不可聲稱已檢查不存在的資料來源。
- 若使用者提到 Redis、外部資料庫或其他規則儲存位置，必須先確認實際連線／檔案／工具是否存在及可存取；不可把 GitHub repo 搜尋結果當成已檢查外部 Redis。

## 9. 每次開發結束必須回報

以精簡但完整的格式回報：
- **檢查基準：** branch、HEAD SHA、已讀規範／備註。
- **全局判斷：** 本次問題在整體架構中的位置及影響。
- **根因證據：** 日誌、測試、裝置數據或明確標示的假設。
- **修改內容：** 檔案、功能與 commit SHA。
- **驗證結果：** 每項測試／CI 的實際結果及 run ID。
- **未完成事項：** 實機驗收、權限或其他阻塞。
- **下一步：** 依風險與依賴排序，不只列出眼前單點。

## 10. 外部效能專案審查與移植規則（強制）

每當研究或打算採用外部 Android／遊戲效能專案時，必須先依照 README 的「外部專案與技術研究」清單審查，並遵守：

1. **先核對平台適用性**：Android 版本、OEM、SoC、GPU、核心版本、裝置型號及遊戲引擎／渲染 API；「支援 Exynos」不代表支援 Exynos 1480，「支援 Android」不代表支援 One UI 9／Android 17。
2. **查實際權限**：分清 Root、Magisk、Shizuku、ADB Shell UID 2000、OEM 受信任系統服務。不能把「無 Root」等同「Shell 可以寫所有 sysfs」。
3. **讀原始碼，不只讀 README**：確認真正執行的命令、sysfs 路徑、屬性名稱、單位、讀寫權限、呼叫時機、錯誤處理、備份與還原；只讀 README 時必須標記「尚未完成原始碼審查」。
4. **核對版本與維護狀態**：查看最近 commit、release、changelog、Issue／PR、Android API 適配與已知故障。過時專案可借鑑演算法概念，但不可當成可直接執行的方案。
5. **檢查授權條款**：若要複製程式碼或資產，先確認 License、NOTICE、相依套件授權及必要歸屬；只借鑑概念也要記錄來源。
6. **拆分概念與實作**：標示「可採納設計」「需 A55 實測」「不採用」；不得因別款手機成功就直接移植 CPU／GPU 頻率表或硬體節點。
7. **先做最小、可回復的驗證**：任何新控制先讀取原始值、驗證裝置與權限、備份、寫入、讀回；讀回不可用時不得假裝驗證成功；失敗必須還原。
8. **禁止照搬危險操作**：不得引入關閉 thermal、thermal override 偽造狀態、停用 Samsung GOS、殺背景程序、強制清 RAM、未驗證的 CPU affinity／cpuset／uclamp／scheduler 寫入、跨 OEM SurfaceFlinger 注入或未授權的遊戲檔案修改。
9. **驗收必須對準兩個產品目標**：比較相同場景的 frame-time／呈現間隔尖峰、掉幀與長時穩態；不得以 UI 數字、跑分、平均 FPS 或 CI 成功代替實機證據。
10. **記錄審查深度與結論**：每個候選專案都要記錄來源 URL、檢查日期、已讀檔案／程式碼、權限需求、相容性、風險、採用決策與尚未驗證事項。

### 2026-11-10 外部研究基準

- Uperf：只保留事件驅動 Hint／SfLag 的概念；不採用 SfAnalysis 注入與 Root 控制。
- PULSE：只保留閉環控制、目標不可達判斷及功耗／順暢度折衷概念；PServer 與 Adreno 控制不適用 A55。
- ApexTuner：只保留交易式備份／回滾的設計參考；CPU／GPU 寫入需要 Root，且其 read-back 不可用時回報成功的行為不符合本專案驗證門檻。
- FrameX：只保留外部 SurfaceFlinger 呈現統計與 ThermalService 診斷作為候選驗收工具；不引入 HUD、RAM 清理或背景程序管理。
- Project Zenith：Game Mode 與指定 App ART 編譯列為獨立驗證候選，不直接納入加速器。
- HyperBoost：需進一步逐項審查原始碼，不可只根據功能列表採用。
- GameCore：採用可信讀值、明確 unavailable 與原值還原的設計原則。
- Game-Booster-v1、ADB-Android-Optimizer：不採用清 RAM、殺程序、HWUI 當成 UE Vulkan 加速或熱保護覆寫等做法。
- Android 官方文件：優先參照 ADPF、Frame Pacing／Swappy、Perfetto／FrameTimeline；必須注意這些 API 有些需要遊戲引擎端整合，外部 App 不具備等價控制能力。

- RADV-Xclipse：雖然列出 Xclipse 530／Exynos 1480 與 Galaxy A55 測試，但套件供能載入自訂 Vulkan driver ZIP 的容器 App 使用，不可假設能替原生鳴潮更換 driver；不得在未確認載入路徑與 GPU hang 風險前嘗試。
- Surface FPS Monitor：SurfaceFlinger TimeStats／Shizuku 500 ms 取樣可作外部驗收參考；原作者測試於 Xiaomi 12T／Android 15，A55 尚未驗證，不能把平均 FPS 當 UE4 內部 GPU 時間。
- GAMA：`debug.hwui.renderer` 是 Android HWUI renderer 設定，不是 UE4 Vulkan renderer 切換；不得用它宣稱鳴潮改用 Vulkan，也要考慮 System UI 重啟／崩潰風險。
- Moha Lab Optimization：LICENSE 是 view-only，不可複製、修改、建置或散布；只能記錄公開描述，不能移植程式碼。
- EliteGaming：單一 commit 與未附可重現測試的高 FPS 增益宣稱不構成證據；Aggressive LMK／殺背景 App 不納入本專案。

- WuWa Config Patcher：Game Resizer 的 per-app downscale 在 SM-A5560 已實測造成遊戲 UI 縮小、操作區域不合用，故淘汰，不得再次套用 0.9 downscale。只保留 CVar 分析／GC guard 概念。Android Game Mode Performance mode 可獨立使用；60 FPS intervention 僅在 OEM 設定未被覆寫、預設 profile 確認為 1.0 scaling／無 angle／無 FPS 時才可套用，必須保存 OEM 快照、讀回驗證並以 reset 還原；效果仍待 A55 A/B。
- Mobile WuWa-Config：V3.7 配置主要測試平台並非 SM-A5560；其 V3.6 forbidden CVar 清單不是 V3.7 權威清單。不得將 Dimensity／Mali／Exynos 1380／2400 設定直接套用 Xclipse 530，必須核對鳴潮 3.7 Client.log。
- Geilan63lan/Wuwa-Mobile-Config：作者標記有兩份由鄰近 preset 推測重建、未驗證的檔案；不可將 All Devices 標籤視為 A55 實測。
- WuWa Tool Android：作者已標示 unmaintained；不作為目前可依賴的效能或維護方案。
- wuwa-tweaks：PC 版工具，不可移植其 PC 資產修改／FPS unlock 到 Android 鳴潮。
- WuWaLab：官方說明不讀遊戲檔案、不自動化遊戲，與效能控制無關。



### 2026-11-10 Game Mode 實作決策

- 目標韌體回報鳴潮可用 mode IDs [1, 2]，目前可用標籤為 standard/performance；採用系統已提供的 Performance mode，不自行寫入 device_config 或建立 intervention。
- 控制流程：讀取目前模式與可用模式 → 僅允許原模式為 standard 且 mode 2 可用 → 持久化備份 → 執行 `cmd game mode performance` → 重新讀回確認；停止時切回原模式、讀回確認後才刪除備份。
- 原本已是 performance 時不建立備份、不宣稱本 App 改變了模式；custom／未知模式一律拒絕修改。
- 0.9 downscale 已在 A55 造成 UI／觸控操作不適，正式排除。Performance mode 是否降低幀時間尖峰仍須實機 A/B，不得預先宣稱有效。

## 開發前檢查紀錄模板

每次開始一項新工作時，先建立並填寫：

| 檢查項目 | 結果／證據 |
|---|---|
| 已閱讀 DEVELOPMENT_RULES.md | |
| 已閱讀 README 與安全限制 | |
| 已閱讀相關原始碼註解／TODO | |
| 已檢查 branch、HEAD、PR base | |
| 已檢查近期 CI 紅叉及完整日誌 | |
| 已檢查 Issue／PR／既有備註 | |
| 已搜尋其他規則／外部規則來源 | |
| 全局影響與依賴關係 | |
| 根因證據與待驗證假設 | |
| 本次修改計畫與回歸測試 | |

未完成必要檢查，不得開始實作。


### 2026-11-10 Performance FPS intervention

- Android 官方 FPS throttling 說明指出，平台 FPS intervention 可改善未自行 frame pacing 遊戲的穩定呈現；遊戲若已自行 pacing，介入可能無效。
- 本專案只考慮 60 FPS，不套用 downscale。只在 device_config game_overlay 原始值為 null，且 cmd game list-configs 顯示 Performance mode scaling=1.0、Use Angle=false、Fps 空白時，才套用 cmd game set --mode 2 --fps 60。
- 寫入前保存並校驗 OEM 預設快照；寫入後必須讀回 Fps=60 且 scaling/angle 不變。停止、遊戲離開前景或錯誤時執行 cmd game reset --mode 2，讀回必須與原快照相同；外部修改時拒絕覆蓋並保留備份。
- AOSP 命令語義依 Android GameManagerShellCommand；實際 One UI 9 行為、鳴潮是否屬未 pacing 遊戲及幀時間收益仍待 SM-A5560 實測。不得把 CI 通過當作實機有效。
