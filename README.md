# DhanWise

> **Local-first, privacy-respecting subscription, benefits, and recurring-spend intelligence engine.**

DhanWise analyses bank and credit-card statements directly on your local machine to identify recurring subscriptions, detect hidden price hikes and FX currency creep, uncover overlapping memberships, and highlight unclaimed perks from credit cards and telecom plans—**with zero cloud dependencies, no mandatory AI API in the critical path, and no bank credential scraping.**

---

```
                                  [ DHANWISE ENGINE ]
 ┌──────────────────────┐         ┌────────────────────────────────────────────────────────┐
 ┌ Bank / Card Statement│───────> │  1. Deterministic Parser (CSV / Multi-format)          │
 │ (Local PDF / CSV)    │         │  2. Merchant Normalizer & Alias Cleanser               │
 └──────────────────────┘         │  3. Cadence Detector (Weekly, 28-Day, Monthly, Annual) │
                                  │  4. FX Decomposition & Stealth Price-Creep Engine      │
 ┌──────────────────────┐         │  5. Versioned Catalogue & Capability Overlap Matcher   │
 │ Community Catalogue  │───────> │  6. CEL Eligibility Engine (Benefits & Credit Cards)   │
 │ (Reviewed JSON Specs)│         └───────────────────────────┬────────────────────────────┘
 └──────────────────────┘                                     │
                                                              ▼
                                  ┌────────────────────────────────────────────────────────┐
                                  │ Findings & Verified Savings Ledger                     │
                                  │  - Auditable Evidence & Explicit Assumptions           │
                                  │  - 4-Tier Savings: Potential / Estimated /             │
                                  │                    Confirmed / Realized                │
                                  │  - Read-Only MCP Tool Server (Optional Local Bridge)   │
                                  └────────────────────────────────────────────────────────┘
```

---

## Key Principles & Design Philosophy

1. **Deterministic Business Logic Over LLM Hallucinations:**
   Subscription tracking and financial math are exact sciences. Recurrence detection, foreign exchange fees, calendar-interval clustering, and duplicate capability checks are evaluated by deterministic algorithms and Common Expression Language (CEL) rules—not probabilistic LLMs.
2. **Backend-Driven & Zero Hardcoding:**
   All currencies, ISO-4217 exponents, merchant alias maps, cadence intervals, pricing tiers, and benefit matrices are driven entirely by migrations, SQLite relational tables, and validated schema specifications. No magical string constants or hardcoded currency math.
3. **Strict Local Isolation:**
   All statements, transactions, and profile records remain strictly on `127.0.0.1`. The application makes **zero outbound network calls** at runtime, contains no telemetry, and refuses to bind to public IP addresses unless explicitly configured.
4. **Honest Financial Accounting:**
   We never collapse distinct financial states into a deceptive "You saved $X" marketing metric. Savings are strictly partitioned into **Potential**, **Estimated**, **Confirmed**, and **Realized**.

---

## Core Capabilities (v0.1)

- **Robust Statement Ingestion:**
  - Automated CSV dialect discovery (comma, semicolon, tab).
  - Handles single-column signed amounts or distinct debit/credit columns.
  - Multi-date format parsing (`YYYY-MM-DD`, `DD/MM/YYYY`, `MM/DD/YYYY`, `DD-Mon-YYYY`).
  - Row-level error isolation: bad rows are flagged with specific line numbers rather than failing the entire batch.
- **Double Deduplication Guarantee:**
  - **File Hash:** SHA-256 fingerprinting prevents re-importing identical statement files.
  - **Transaction Fingerprint:** Composite hashing (`account + booking_date + amount_minor + raw_hash`) prevents duplicate counting across overlapping statement periods.
- **Cadence & Recurrence Classification:**
  - Supports Weekly, Bi-weekly, 28-day billing cycles (standard telecom in India and global carriers), Monthly, Quarterly, and Annual schedules with calendar month-end clamping (Jan 31 $\rightarrow$ Feb 28).
  - Strict confidence scoring: single charges are never flagged as subscriptions.
  - Automated detection of loans / EMI instalments: splits of fixed duration are quarantined from recurring subscriptions.
- **Step-Change & FX Decomposer:**
  - Separates actual vendor price hikes from foreign exchange rate fluctuations and bank card foreign markup fees (in basis points).
  - Flags stealth price creep only when backed by statistical stability across consecutive observation windows.
- **Reviewed Catalogue & Capability Overlap:**
  - Identifies functional redundancy (e.g., holding both Spotify and Apple Music, or paying for Disney+ Hotstar while holding a telecom postpaid plan or credit card that bundles it free).
  - Every catalogue entry is backed by an HTTPS source citation, retrieval timestamp, and reviewer verification state.

---

## Technical Stack & Architecture

- **Backend:** Java 21 LTS, Spring Boot, Spring Modulith.
- **Persistence:** SQLite 3 in WAL mode (`busy_timeout=5000`), managed via jOOQ and an append-only, checksummed migration engine (`SchemaMigrator`).
- **Rule Evaluation:** Common Expression Language (CEL) for benefit eligibility and card tier conditions.
- **Frontend:** React 18, TypeScript, Vite (embedded within the backend JAR for single-binary self-hosting).
- **Interface Protocol:** Optional Read-Only MCP (Model Context Protocol) server over `stdio` for external AI agent queries with zero network exposure.

---

## Quick Start

### Prerequisites
- **JDK 21+**
- **Node.js 20+**
- **Maven 3.9+** (or use bundled wrapper)
- *(Optional)* **Docker & Docker Compose**

### 1. Build and Run Locally

```bash
# Clone the repository
git clone https://github.com/manavidubey/-dhanwise.git
cd dhanwise

# Build SPA frontend, copy assets to backend, compile and launch:
make run
```

Or execute manually:

```bash
# 1. Build the frontend
cd web
npm install
npm run build
cd ..

# 2. Sync static assets to Spring resources
rm -rf src/main/resources/static && mkdir -p src/main/resources/static
cp -R web/dist/. src/main/resources/static/

# 3. Launch Spring Boot backend
mvn spring-boot:run
```

Once started, navigate to: **`http://127.0.0.1:8080`**  
Click **"Load sample data"** to immediately test 36 realistic multi-currency transactions, price increases, and telecom/card capability overlaps.

### 2. Run with Docker Compose

DhanWise runs inside a hardened container with a non-root user, a read-only root filesystem, and loopback-only binding:

```bash
docker compose up --build
```

---

## Detection & Decision Rules

| Signal / Dimension | Decision Rule & Threshold |
|---|---|
| **Minimum History** | Minimum 2 recurring occurrences. 2 observations alone cap confidence at `0.55`. |
| **Cadence Fit** | $\ge 80\%$ gap consistency for $\le 3$ intervals; $\ge 60\%$ consistency for larger sample sizes. |
| **Amount Stability** | Variance wider than 40% of the median flags the series as `variable_amount` (capped at `0.80` confidence). |
| **Auto-Promotion** | Automated promotion to active subscription requires $\ge 0.70$ confidence and stable amounts. |
| **Price Hike Verification** | Requires $\ge 4$ charges where the last two agree within 2% and the step difference exceeds 3%. |
| **EMI / Loan Quarantine** | Fixed-count amortized series (e.g., 3, 6, 9, 12 months) are tagged as loans and excluded from subscriptions. |

---

## The Four-Tier Savings Ledger

DhanWise refuses to add unverified numbers together. Savings are reported in 4 explicit categories:

1. **`Potential`**: Redundancies or unlinked perks identified by the engine, awaiting user review.
2. **`Estimated`**: Modelled savings based on explicitly stated catalogue assumptions and usage profiles.
3. **`Confirmed`**: Subscriptions marked cancelled or changed by the user where subsequent billing confirmation has not yet occurred.
4. **`Realized`**: Future statement imports confirm that the recurring charge is absent beyond the renewal window.

---

## Security & Threat Model

- **Local Host Protection:** Binds exclusively to `127.0.0.1`. Non-loopback binding requires setting `SUB_SCOUT_ALLOW_NON_LOOPBACK=true` and emits high-visibility security warnings.
- **No Third-Party Token Leaks:** Statements are never shipped to cloud APIs. If local LLM enrichment (e.g., Ollama) is used, all account numbers, PANs, card numbers, and PII are scrubbed locally beforehand.
- **Physical Disk Security:** SQLite database files (`dhanwise.db`, `dhanwise.db-wal`) contain sensitive personal spend history. Running DhanWise on an OS volume protected with full-disk encryption (FileVault / BitLocker / LUKS) is strongly advised.

---

## Configuration Reference

All settings can be configured via environment variables or `application.yml`:

| Environment Variable | Default Value | Description |
|---|---|---|
| `SUB_SCOUT_DB_PATH` | `./dhanwise.db` | Path to the SQLite database file |
| `SUB_SCOUT_BIND` | `127.0.0.1` | Network interface address |
| `SUB_SCOUT_PORT` | `8080` | Web server listening port |
| `SUB_SCOUT_ALLOW_NON_LOOPBACK` | `false` | Explicit opt-in override for non-loopback network exposure |

---

## Verification & Test Suite

Run the full automated test suite covering currency exponents, cadence classification, step-change detection, merchant alias normalization, and schema integrity:

```bash
mvn test
```

```
[INFO] Running dev.subscout.ledger.CsvStatementParserTest
[INFO] Running dev.subscout.platform.MoneyTest
[INFO] Running dev.subscout.platform.SqlScriptTest
[INFO] Running dev.subscout.insight.AnnualSwitchSavingsTest
[INFO] Running dev.subscout.insight.OverlapRecommendationTest
[INFO] Running dev.subscout.insight.FxDecomposerTest
[INFO] Running dev.subscout.insight.SavingsLedgerTest
[INFO] Running dev.subscout.deals.DealsIntelligenceTest
[INFO] Running dev.subscout.recurring.CadenceTest
[INFO] Running dev.subscout.recurring.RecurrenceDetectorTest
[INFO] Running dev.subscout.recurring.RecurrencePrecisionBenchmarkTest
[INFO] Running dev.subscout.merchant.MerchantNormalizerTest
[INFO] Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
```

---

## Project Structure

```
dhanwise/
├── src/main/java/dev/subscout/
│   ├── platform/     # Money, ISO-4217 Currencies, IDs, SchemaRunner, Bind Safety
│   ├── ledger/       # CSV Statement Parsing, Import Deduplication, Transaction Ledger
│   ├── merchant/     # Deterministic Normalization, Alias Resolution
│   ├── recurring/    # Interval Math, Cadence Detection, EMI Isolation, Promotion
│   ├── insight/      # Findings Engine, 4-Tier Savings Ledger, FX Decomposition
│   ├── catalogue/    # Provider/Tier/Perk Catalog Specs, Source Validation
│   └── api/          # REST Controllers, DTOs, Frontend Endpoints
├── src/main/resources/
│   ├── db/migration/ # Versioned V<n>__*.sql schema definitions
│   ├── catalogue/    # Reviewed catalogue definitions (catalogue.json)
│   └── samples/      # Verified statement benchmark fixtures
├── web/              # React 18 + TypeScript + Vite SPA interface
├── Dockerfile        # Multi-stage hardened non-root container build
├── docker-compose.yml
└── Makefile          # Single-command development and packaging workflows
```

---

## Contributing

We welcome contributions! Please note:
- All pricing and plan changes must be submitted as pull requests against `src/main/resources/catalogue/catalogue.json`.
- Every added or modified offer must contain a valid `https` citation URL, retrieval timestamp, and a designated reviewer state. Automated scraper dumps without human verification will be rejected.
