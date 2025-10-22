# BSS 團隊會議討論問題總結

**會議目標：** 對齊 BSS 與 Storage 團隊的 IAM 整合策略

**會議時間：** 90 分鐘

**參與者：**
- BSS 團隊：產品負責人、技術主管、後端工程師
- Storage 團隊：產品負責人、技術主管、後端工程師
- 選擇性：安全主管、合規官

---

## 第一部分：架構對齊（30 分鐘）

### 問題 1.1：BSS RBAC 系統概述

**背景：** 我們了解 BSS 有自己的 RBAC 系統。我們需要了解它的運作方式以便正確整合。

**詢問 BSS 的問題：**

1. **BSS 目前有哪些 RBAC 角色？**
   - 我們假設的角色：CustomerAdmin（客戶管理員）、Developer（開發者）、Viewer（檢視者）、ComplianceOfficer（合規官）、Finance（財務）
   - 這些假設正確嗎？還有其他角色嗎？
   - 能否分享完整的角色清單及說明？

2. **BSS 角色如何分配給使用者？**
   - 誰可以分配角色？（只有 CustomerAdmin？還是 BSS 管理員？）
   - 使用者可以擁有多個角色嗎？
   - 角色可以按服務分配嗎？（例如：只限 Storage 的 Developer）

3. **BSS 角色在 BSS 系統中有哪些權限？**
   - CustomerAdmin 在 BSS 入口網站可以做什麼？
   - Developer 可以做什麼？
   - BSS 角色是否控制所有服務（storage、GPU、VM、containers）的存取，還是只控制 BSS 入口網站功能？

**我們的提案：**
- Storage 將透過配置檔（`bss-role-mapping.yaml`）**映射** BSS 角色到 S3 權限
- 範例：`Developer` BSS 角色 → 可以 `s3:GetObject`、`s3:PutObject`、`s3:DeleteObject`，但**不能**存取 `/compliance/*` 儲存桶
- BSS 不需要理解 S3 特定的權限（s3:GetObject vs s3:PutObject）
- Storage 維持對 S3 授權的完全控制

**❓ 問題：** 這種映射方式對 BSS 可行嗎？還是 BSS 需要直接管理 S3 權限？

---

### 問題 1.2：多服務整合模式

**背景：** 你們的資料中心有多個服務（Storage、GPU、VM、Containers）。當 BSS 建立客戶時，應該如何配置服務？

**問題所在：**
- ❌ **反模式：** BSS 直接呼叫每個服務的 API（Storage、GPU、VM、Containers）
  - 這會造成緊密耦合
  - 如果某個服務當機，客戶建立會失敗
  - BSS 需要知道每個服務的 API

**詢問 BSS 的問題：**

1. **BSS 目前有訊息代理（Message Broker）嗎？（Kafka、RabbitMQ、NATS）**
   - 如果有，存在哪些主題（topics）/交換（exchanges）？
   - BSS 可以發布客戶生命週期事件嗎？

2. **BSS 願意提供客戶元資料的唯讀 REST API 嗎？**
   - 範例：`GET /api/v1/customers/{customerId}`
   - 回傳：客戶狀態、權限、配額、層級
   - Storage 會按需呼叫此 API（延遲配置）

3. **預期的客戶導入量是多少？**
   - 每天 10 個客戶？還是 1000 個客戶？
   - 這有助於我們在拉取式（簡單）與事件串流式（可擴展）之間做選擇

**我們的提案（兩個選項）：**

**選項 A：拉取式（建議用於簡單性）**
- BSS 在 BSS 資料庫中建立客戶，包含所有服務的權限
- BSS 發出帶有 `customer_id` 聲明的 OAuth2 JWT
- Storage 在第一個 S3 請求到達時**按需**拉取客戶元資料
- Storage 延遲建立租戶（即時配置）
- GPU、VM、Container 服務獨立執行相同操作
- **優點：** 簡單，不需要訊息代理，適用於 < 5 個服務
- **缺點：** 第一次請求增加 50-200ms（透過快取緩解）

**選項 B：事件串流式（建議用於規模化）**
- BSS 發布 `customer.created` 事件到 Kafka
- 每個服務獨立訂閱
- 每個服務在收到事件時建立自己的租戶
- **優點：** 完全解耦，主動配置，可擴展到 10+ 個服務
- **缺點：** 需要 Kafka，最終一致性（1-5 秒延遲）

**❓ 問題：** BSS 偏好哪種模式？你們已經有 Kafka 基礎設施了嗎？

---

### 問題 1.3：服務權限

**背景：** 客戶可能不具備所有服務的權限。權限如何管理？

**詢問 BSS 的問題：**

1. **服務權限如何決定？**
   - 按客戶層級（basic、premium、enterprise）？
   - 按每個客戶的明確配置？
   - 權限在客戶建立後可以變更嗎？（層級升級）

2. **如果使用者嘗試存取他們沒有權限的服務，應該發生什麼？**
   - Storage 應該回傳 403 Forbidden 嗎？
   - BSS 入口網站應該完全隱藏該服務嗎？

3. **配額/限制如何管理？**
   - BSS 設定儲存配額嗎？（例如：1TB、10TB）
   - 客戶可以請求增加配額嗎？
   - 誰核准配額增加？

**我們的提案：**
- BSS 在客戶元資料 API 中公開權限：
  ```json
  {
    "customer_id": "acme-123",
    "entitlements": {
      "storage": {"enabled": true, "quotaGB": 1000},
      "gpu": {"enabled": true, "gpuHours": 500},
      "vm": {"enabled": false},
      "container": {"enabled": true, "maxPods": 50}
    }
  }
  ```
- Storage 在建立租戶前檢查 `entitlements.storage.enabled`
- 如果未啟用，回傳 403 Forbidden

**❓ 問題：** 這個權限模型對 BSS 可行嗎？

---

## 第二部分：API 契約（30 分鐘）

### 問題 2.1：BSS → Storage API 契約

**背景：** BSS 需要 Storage 提供哪些 API？

**我們的提案：**

**API 1：產生 S3 存取金鑰**
```yaml
POST /api/v1/tenants/{tenantId}/access-keys
請求：
  userId: string（例如：alice@acme.com）
  expiryDays: number（選擇性）
回應：
  accessKeyId: string（例如："acme-123$alice"）
  secretAccessKey: string（僅回傳一次！）
  createdAt: timestamp
```
- **誰呼叫：** BSS 入口網站，當使用者點擊「產生 S3 憑證」時
- **授權：** BSS 服務帳戶令牌（M2M）

**API 2：取得儲存使用指標**
```yaml
GET /api/v1/tenants/{tenantId}/metrics
回應：
  totalBytes: number
  quotaBytes: number
  requestCounts:
    s3GetObject: number
    s3PutObject: number
```
- **誰呼叫：** BSS 計費系統（每日批次）
- **授權：** BSS 服務帳戶令牌（M2M）

**❓ 問題：**
1. 這些 API 對 BSS 的需求足夠嗎？
2. BSS 需要其他 Storage API 嗎？（例如：列出儲存桶、刪除租戶）
3. BSS 應該如何向 Storage 驗證？（我們建議使用 M2M 服務帳戶令牌）

---

### 問題 2.2：Storage → BSS API 契約

**背景：** Storage 需要 BSS 提供哪些 API？

**我們的提案（針對拉取式模式）：**

**API 1：取得客戶元資料**
```yaml
GET /api/v1/customers/{customerId}
回應：
  customerId: string
  name: string
  tier: string（basic|premium|enterprise）
  status: string（active|suspended|deleted）
  adminEmail: string
  entitlements:
    storage:
      enabled: boolean
      quotaGB: number
    gpu:
      enabled: boolean
    # ... 其他服務
  createdAt: timestamp
```
- **誰呼叫：** Storage，當第一個 S3 請求到達時
- **授權：** Storage 服務帳戶令牌（M2M）
- **快取：** Storage 快取回應 15 分鐘

**API 2（選擇性）：取得使用者詳細資料**
```yaml
GET /api/v1/users/{userId}
回應：
  userId: string
  email: string
  customerId: string
  bssRoles: [string]
  status: string（active|suspended）
```
- **誰呼叫：** Storage 用於使用者驗證（選擇性）

**API 3（選擇性）：取得客戶拒絕策略**
```yaml
GET /api/v1/policies/customer/{customerId}
回應：
  denyRules: [
    {
      sid: string
      effect: "Deny"
      action: string
      resource: string
      condition: object
    }
  ]
```
- **誰呼叫：** Storage，如果 BSS 想要強制執行動態拒絕規則

**❓ 問題：**
1. BSS 可以公開這些唯讀 API 嗎？
2. Storage 應該使用什麼驗證機制？（我們建議預共用的服務帳戶令牌）
3. 有我們應該注意的速率限制嗎？

---

### 問題 2.3：JWT 令牌聲明

**背景：** Storage 將驗證 BSS Keycloak 發出的 JWT。應該包含哪些聲明？

**我們的提案：**
```json
{
  "iss": "https://bss-keycloak.example.com/realms/datacenter",
  "sub": "alice@acme.com",
  "aud": "ozone-storage",
  "exp": 1734260000,
  "customer_id": "acme-123",
  "bss_roles": ["Developer"],
  "entitlements": {
    "storage": true,
    "gpu": true,
    "vm": false
  }
}
```

**❓ 問題：**
1. BSS Keycloak 可以包含這些自訂聲明嗎？
2. 權限應該在 JWT 中還是透過 API 取得？（JWT 更快但較不動態）
3. JWT 過期時間是多少？（我們建議 Admin API 1 小時，入口網站 12 小時）

---

## 第三部分：合規與安全（20 分鐘）

### 問題 3.1：BSS 拒絕規則

**背景：** BSS 可能想要強制執行覆蓋 Storage 權限的合規限制。

**使用案例範例：**
- 「開發者不能存取 `/compliance/*` 儲存桶」（合規分離）
- 「暫停的客戶不能存取任何服務」（計費強制執行）
- 「只有 CustomerAdmin 可以刪除 `/production/*` 中的物件」（資料保護）

**詢問 BSS 的問題：**

1. **BSS 需要強制執行這些類型的拒絕規則嗎？**
   - 如果需要，應該是靜態的（在 Storage 中配置）還是動態的（從 BSS 查詢）？

2. **Storage 應該如何知道這些規則？**
   - **選項 A：** 在 Storage 的 `bss-role-mapping.yaml` 中靜態配置：
     ```yaml
     Developer:
       deny_rules:
         - resource: "*/compliance/*"
           reason: "合規資料僅限 ComplianceOfficer 角色存取"
     ```
   - **選項 B：** 動態 API 呼叫到 BSS：
     ```
     GET /api/v1/policies/customer/{customerId}
     ```

**我們的建議：**
- 從**選項 A（靜態）**開始，保持簡單
- 如果需要複雜的合規規則，稍後再加入**選項 B（動態）**

**❓ 問題：** BSS 偏好哪個選項？有我們應該知道的合規要求嗎？

---

### 問題 3.2：稽核日誌

**背景：** 兩個團隊都需要稽核日誌以符合合規（SOC2、GDPR、HIPAA）。

**詢問 BSS 的問題：**

1. **BSS 需要 Storage 提供哪些稽核事件？**
   - S3 API 呼叫（GetObject、PutObject、DeleteObject）？
   - 租戶生命週期事件（建立、暫停、刪除）？
   - 存取金鑰事件（產生、撤銷）？

2. **Storage 應該如何提供這些日誌？**
   - **選項 A：** Storage 推送到集中式日誌（Splunk、ELK、DataDog）
   - **選項 B：** Storage 透過 API 公開日誌（`GET /api/v1/tenants/{id}/audit-logs`）
   - **選項 C：** Storage 發布到 Kafka 主題（`storage.audit.logs`）

3. **保留政策是什麼？**
   - 90 天？1 年？7 年（合規）？

**我們的提案：**
- Storage 記錄所有 S3 API 呼叫，包含 BSS 角色上下文：
  ```json
  {
    "timestamp": "2025-10-15T10:30:00Z",
    "principal": {
      "user": "alice@acme.com",
      "tenant": "acme-123",
      "bss_roles": ["Developer"]
    },
    "action": "s3:PutObject",
    "resource": "arn:aws:s3:::bucket/file.txt",
    "result": "ALLOW"
  }
  ```
- Storage 保留日誌 90 天
- BSS 可以透過 API 查詢日誌或從集中式日誌拉取

**❓ 問題：** BSS 偏好哪種日誌傳遞方式？

---

## 第四部分：測試與部署（10 分鐘）

### 問題 4.1：測試環境

**詢問 BSS 的問題：**

1. **BSS 有測試環境嗎？**
   - 我們可以在正式環境之前與測試環境的 BSS 進行整合測試嗎？
   - 我們可以在測試環境取得測試客戶帳戶嗎？

2. **Storage 如何從 BSS 測試環境的 Keycloak 取得測試 JWT？**
   - BSS 可以提供測試使用者帳戶嗎？
   - BSS 可以提供 Storage → BSS API 呼叫的服務帳戶令牌嗎？

**我們的提案：**
- 第 1 週：設定測試環境整合（BSS 測試 + Storage 測試）
- 第 2 週：使用 5 個測試客戶進行測試
- 第 3 週：100 個並發使用者的負載測試
- 第 4 週：正式環境部署（10% → 50% → 100%）

**❓ 問題：** 這個時間表對 BSS 可行嗎？

---

### 問題 4.2：正式環境部署

**詢問 BSS 的問題：**

1. **應該進行分階段部署還是一次性部署？**
   - 分階段：從 10% 的客戶開始，監控，逐步增加
   - 一次性：所有客戶一次性部署

2. **回滾標準是什麼？**
   - 如果授權錯誤率 > 1%？
   - 如果第一次請求延遲 > 500ms？

3. **部署期間誰應該待命？**
   - BSS 團隊需要監控 OAuth2/JWT 發放
   - Storage 團隊需要監控租戶配置和授權

**我們的建議：**
- 分階段部署：10% → 50% → 100%，歷時 2 週
- 部署期間聯合作戰室（BSS + Storage 團隊在同一個 Slack 頻道）
- 回滾計畫：功能標記以停用 BSS 整合並回退到手動租戶配置

**❓ 問題：** BSS 同意這個部署策略嗎？

---

## 第五部分：開放問題與行動項目（10 分鐘）

### 開放問題

1. **BSS 有任何現有的 OAuth2/OIDC 整合可以讓我們學習嗎？**
   - GPU/VM 服務如何與 BSS 整合？
   - 有什麼經驗教訓？

2. **未來新增 BSS 角色的流程是什麼？**
   - 範例：如果 BSS 新增「DevOps」角色，Storage 應該如何知道？
   - 我們需要更新 `bss-role-mapping.yaml` 嗎？

3. **客戶層級升級如何處理？**
   - 如果客戶從 basic（100GB）升級到 enterprise（1TB），Storage 如何被通知？
   - 透過事件？還是 Storage 在下次請求時拉取？

4. **客戶離職的流程是什麼？**
   - Storage 應該立即刪除租戶資料還是保留 30 天？
   - 誰核准資料刪除？

---

## 行動項目範本

**BSS 團隊：**
- [ ] 分享完整的 BSS 角色清單及說明
- [ ] 確認是否存在 Kafka 基礎設施（用於事件串流模式）
- [ ] 提供 BSS 客戶 API 的 OpenAPI 規格（如果公開）
- [ ] 設定測試環境的 Keycloak 供 Storage 測試
- [ ] 提供 Storage → BSS API 呼叫的服務帳戶令牌
- [ ] 審查並核准 `bss-role-mapping.yaml` 配置

**Storage 團隊：**
- [ ] 實作 BSSClient（拉取式模式）或 BSSEventConsumer（事件串流）
- [ ] 實作帶 JWT 驗證的 OAuth2Filter
- [ ] 實作帶 `bss-role-mapping.yaml` 的 RoleMapper
- [ ] 建立 Storage API（存取金鑰、指標）的 OpenAPI 規格
- [ ] 設定 BSS 整合測試的測試環境
- [ ] 提供 BSS → Storage API 呼叫的服務帳戶端點

**聯合：**
- [ ] 安排後續會議審查 API 契約
- [ ] 設定共用 Slack 頻道進行整合討論
- [ ] 安排整合測試會議（[日期] 的那週）
- [ ] 安排正式環境部署規劃會議

---

## 決策矩陣

| 決策 | 選項 A | 選項 B | 選項 C | 建議 | 原因 |
|------|--------|--------|--------|------|------|
| **BSS 整合模式** | 拉取式 | 事件串流式 | 混合式 | **選項 A**（開始），如需要遷移到 C | 最簡單實作，無 Kafka 依賴 |
| **BSS 角色管理** | 靜態映射 | 動態 API | JWT 聲明 | **選項 A** | 最容易維護，BSS 不管理 S3 權限 |
| **BSS 拒絕規則** | 靜態配置 | 動態 API | 兩者 | **選項 A**（MVP），稍後加入 B | 從簡單開始，如需要再加入複雜性 |
| **稽核日誌傳遞** | API | Kafka | 集中式日誌 | **選項 C** | 多團隊可見性最佳 |
| **部署策略** | 一次性 | 分階段（10-50-100%） | 按服務 | **選項 B** | 最安全，可輕鬆回滾 |

---

## 會議成功標準

會議結束時，我們應該達成：
- ✅ 就 BSS 整合模式達成共識（拉取式 vs 事件串流式）
- ✅ 確認 BSS 角色及其與 Storage 權限的映射
- ✅ 定義 API 契約（BSS → Storage、Storage → BSS）
- ✅ 就 JWT 聲明格式達成共識
- ✅ 確定誰建構什麼（責任劃分）
- ✅ 設定測試環境整合和測試的時間表
- ✅ 安排後續會議

---

## 快速參考：會議議程摘要

### 第一部分：架構對齊（30 分鐘）

**1.1 BSS RBAC 系統概述**
- 了解 BSS 現有的角色清單（CustomerAdmin、Developer、Viewer 等）
- 確認角色分配機制（誰可以分配？是否支援多角色？）
- 釐清 BSS 角色的權限範圍（跨服務或僅 BSS 入口網站？）
- **目標：** 確認 Storage 的角色映射方式是否可行

**1.2 多服務整合模式**
- 探討 BSS 是否有 Kafka 等訊息代理基礎設施
- 確認 BSS 能否提供客戶元資料的唯讀 REST API
- 了解預期的客戶導入量（影響選擇拉取式或事件串流式）
- **目標：** 選定整合模式（拉取式簡單但有延遲，事件串流式可擴展但需 Kafka）

**1.3 服務權限**
- 確認服務權限的決定方式（按層級或明確配置？）
- 討論未授權存取的處理方式（403 Forbidden 或隱藏服務？）
- 釐清配額管理流程（誰設定？誰核准增加？）
- **目標：** 定義權限模型的 JSON 結構

---

### 第二部分：API 契約（30 分鐘）

**2.1 BSS → Storage API 契約**
- **產生 S3 存取金鑰 API**：BSS 入口網站呼叫以產生使用者憑證
- **取得儲存使用指標 API**：BSS 計費系統呼叫以取得用量資料
- **目標：** 確認這些 API 是否滿足 BSS 需求，定義驗證機制（M2M 令牌）

**2.2 Storage → BSS API 契約**
- **取得客戶元資料 API**：Storage 按需拉取客戶狀態、權限、配額
- **取得使用者詳細資料 API**（選擇性）：驗證使用者並取得角色
- **取得客戶拒絕策略 API**（選擇性）：動態取得合規限制規則
- **目標：** 確認 BSS 能否提供這些 API，討論快取策略（15 分鐘 TTL）

**2.3 JWT 令牌聲明**
- 確認 JWT 中需包含的欄位（customer_id、bss_roles、entitlements）
- 討論權限應內嵌 JWT 或透過 API 取得（速度 vs 動態性）
- 確定 JWT 過期時間（建議 Admin API 1 小時，入口網站 12 小時）
- **目標：** 定義標準 JWT 格式供 Storage 驗證

---

### 第三部分：合規與安全（20 分鐘）

**3.1 BSS 拒絕規則**
- 確認 BSS 是否需要強制執行覆蓋性的拒絕規則（合規分離、計費強制執行）
- 討論規則的配置方式（靜態 yaml 配置或動態 API 查詢）
- **目標：** 選定方案（建議從靜態開始，需要時再加入動態）

**3.2 稽核日誌**
- 確認 BSS 需要的稽核事件類型（S3 API 呼叫、租戶生命週期、金鑰管理）
- 討論日誌傳遞方式（推送到集中式日誌、API 查詢或 Kafka）
- 確定保留期限（90 天、1 年或更長？）
- **目標：** 定義日誌格式和傳遞機制（建議集中式日誌以利多團隊檢視）

---

### 第四部分：測試與部署（10 分鐘）

**4.1 測試環境**
- 確認 BSS 測試環境的可用性及測試客戶帳戶
- 討論如何取得測試 JWT 和服務帳戶令牌
- **目標：** 建立 4 週測試時程（設定 → 測試 5 客戶 → 負載測試 → 部署）

**4.2 正式環境部署**
- 選定部署策略（分階段 10-50-100% 或一次性）
- 定義回滾標準（錯誤率 > 1% 或延遲 > 500ms）
- 確認待命安排（BSS 監控 JWT、Storage 監控授權）
- **目標：** 達成分階段部署共識，建立聯合作戰室（共用 Slack 頻道）

---

### 第五部分：開放問題與行動項目（10 分鐘）

**開放問題**
- BSS 現有整合案例的經驗教訓（GPU/VM 如何整合？）
- 未來新增角色的流程（需要更新配置檔嗎？）
- 客戶層級升級的通知機制（事件或下次請求時拉取？）
- 客戶離職的資料保留政策（立即刪除或保留 30 天？）
- **目標：** 釐清流程，避免未來爭議

**行動項目檢查清單**
- **BSS 團隊**：分享角色清單、確認 Kafka、提供 API 規格、設定測試環境
- **Storage 團隊**：實作 BSSClient/OAuth2Filter、建立 API 規格、設定測試環境
- **聯合**：安排後續會議、設定 Slack 頻道、規劃整合測試
- **目標：** 每個團隊離開會議時都清楚知道自己的待辦事項

---

## 決策檢查清單

會議結束前確認以下決策已達成：

- [ ] **整合模式**：拉取式 / 事件串流式 / 混合式？
- [ ] **角色管理**：靜態映射 / 動態 API / JWT 聲明？
- [ ] **拒絕規則**：靜態配置 / 動態 API / 兩者皆有？
- [ ] **日誌傳遞**：API / Kafka / 集中式日誌？
- [ ] **部署策略**：一次性 / 分階段 / 按服務？
- [ ] **測試時程**：4 週計畫可行嗎？
- [ ] **API 契約**：BSS 和 Storage 需提供的 API 已確認？
- [ ] **JWT 格式**：必要欄位已定義？

---

## 總結

這份文件涵蓋了 BSS 與 Storage 團隊整合所需討論的所有關鍵問題。使用此文件作為會議議程，確保討論有建設性並產生明確的下一步行動。

**主要建議：**
1. **從拉取式整合模式開始**（簡單、無需 Kafka，適合 < 5 個服務）
2. **使用靜態角色映射**（`bss-role-mapping.yaml`，易於維護）
3. **分階段部署**（10% → 50% → 100%，可安全回滾）
4. **設定聯合測試環境和 Slack 頻道**（確保溝通順暢）
5. **定期同步會議確保對齊**（每週或每兩週一次）

**參考文件：** IAM_MULTISERVICE_DESIGN.md （完整技術設計）
