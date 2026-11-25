# 繁體中文（台灣）維護約定

台灣語言選項為 `TRADITIONAL_CHINESE_TAIWAN`，資源放在 `values-b+zh+Hant+TW`，使用 `zh-Hant-TW`。香港選項保留原有 `TRADITIONAL_CHINESE` 設定值及 `values-b+zh+Hant` 資源，使用 `zh-Hant-HK`。新增語言列舉值只能接在最後，避免影響既有設定。

台灣版以台灣常用的軟體介面詞彙維護，避免只做簡繁字形轉換：

| 概念 | 台灣用詞 |
| --- | --- |
| Settings / Software | 設定／軟體 |
| Database / Data | 資料庫／資料 |
| File / Folder | 檔案／資料夾 |
| Network / Port | 網路／連接埠 |
| Email / Clipboard | 電子郵件／剪貼簿 |
| Identity / Biometrics | 身分／生物辨識 |
| Login account | 帳號 |
| Financial account | 帳戶 |
| Key / Token | 金鑰／權杖 |
| Technical support / Donation support | 支援／支持、贊助 |

品牌、協定、檔名副檔名、API 名稱、格式參數和 `{USERNAME}` 等具名權杖保留原文。依語境處理「帳號／帳戶」及「支援／支持」，不使用全域取代更新所有語言。

新增可翻譯資源時同步補齊台灣版。`LocaleResourceCoverageTest.taiwanIncludesEveryCurrentResourceWithItsOriginalFormatContract` 檢查完整鍵集、資源類型、格式參數、具名權杖、復數及換行。更新設備用的 `taiwan-resource-contract.json`，由 `TaiwanLocaleInstrumentedTest` 核對實際安裝包中的字串、陣列、復數和格式化結果。

沒有明確 script 的 `zh-TW` 及 `zh-Hant-TW` 使用台灣語言；`zh-Hans-TW` 仍是簡體。香港、澳門與沒有指定地區的 Hant 維持既有語言判定。Android 可將香港／澳門缺少的 Hant 譯文回退到已打包的台灣版，既有香港譯文仍優先使用。

普通版與 F-Droid 分別維護資源，保留平台專用說明和策略差異。機器轉換與開發者用詞檢查不等同台灣母語者的全量校對；後續修詞應保留警告條件及功能語義。
