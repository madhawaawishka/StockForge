# Service Level Objectives (SLOs) & Error Budget Policy

StockForge platform reliability goals, Service Level Indicators (SLIs), Service Level Objectives (SLOs), and Error Budget burn rate alert policies (spec §25, §37).

---

## 1. Objectives Matrix

| Objective ID | Target Metric / SLI | Target Objective (SLO) | Measurement Window | Alert Condition |
| :--- | :--- | :--- | :--- | :--- |
| **SLO-1: Reservation Latency** | P99 latency of `POST /api/v1/reservations` | **< 50ms** | Rolling 30 days | `histogram_quantile(0.99, ...) > 0.05` for 1m |
| **SLO-2: Stock Conservation** | Consistency check: `available + reserved + sold == total` & `available >= 0` | **100% (Zero tolerance)** | Continuous (1m reconciliation) | `stockforge_inventory_invariant_violations > 0` |
| **SLO-3: Read Availability** | HTTP 2xx/3xx rate for catalog and availability queries (`GET /api/v1/products/**`) | **99.9%** (P50 < 10ms) | Rolling 30 days | 5xx error rate > 0.1% over 5m |
| **SLO-4: Outbox Relay Latency** | Transaction commit timestamp to Kafka cluster publication time | **P99 < 500ms** | Rolling 30 days | `stockforge_outbox_oldest_pending_age_seconds > 60` |

---

## 2. SLI Specifications

### SLO-1: Reservation Latency
- **SLI Formula:**
  $$\text{SLI}_{\text{Reservation}} = \frac{\sum \text{rate}(http\_server\_requests\_seconds\_bucket\{uri \approx "/api/v1/reservations.*", le="0.05"\}[30d])}{\sum \text{rate}(http\_server\_requests\_seconds\_count\{uri \approx "/api/v1/reservations.*"\}[30d])}$$
- **Rationale:** During high-concurrency flash sales, customers experience friction if checkout locks exceed human perceptual thresholds (50ms).
- **Measurement Point:** API Gateway boundary before reverse-proxying over gRPC.

### SLO-2: Stock Conservation Invariant
- **SLI Formula:**
  $$\text{SLI}_{\text{Conservation}} = 1.0 - \left(\frac{\text{Minutes with } stockforge\_inventory\_invariant\_violations > 0}{43,200 \text{ min}}\right)$$
- **Target:** Exactly **100%**. StockForge strictly forbids overselling. A single oversold SKU constitutes an immediate P1 incident.

### SLO-3: Read Availability & Latency
- **SLI Formula:**
  $$\text{SLI}_{\text{Availability}} = \frac{\sum \text{rate}(http\_server\_requests\_seconds\_count\{uri \approx "/api/v1/products.*", status!\approx "5.."\}[30d])}{\sum \text{rate}(http\_server\_requests\_seconds\_count\{uri \approx "/api/v1/products.*"\}[30d])}$$
- **Target:** **99.9%** success rate. Latency target: P50 < 10ms (serviced by Redis cache with graceful degradation to PostgreSQL).

### SLO-4: Outbox Delivery Latency
- **SLI Formula:**
  $$\text{SLI}_{\text{Outbox}} = \frac{\text{Outbox events published within 500ms}}{\text{Total outbox events created}}$$
- **Metric Indicator:** `stockforge_outbox_oldest_pending_age_seconds`. When this gauge exceeds 60s, transactional outbox events are delayed, causing downstream saga delays in order confirmation.

---

## 3. Multi-Window Multi-Burn-Rate Alerting

Following Google SRE best practices, alerts fire based on error budget consumption speed rather than static thresholds, preventing alert fatigue while catching severe outages before budget depletion:

| Severity | Burn Rate | % Budget Consumed | Time to 100% Budget Exhaustion | Short Window | Long Window | Notification Channel |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Critical (Page)** | **14.4x** | 2% in 1 hour | 2 days | 5 minutes | 1 hour | PagerDuty / On-call rotation |
| **Critical (Page)** | **6x** | 5% in 6 hours | 5 days | 30 minutes | 6 hours | PagerDuty / On-call rotation |
| **Warning (Ticket)** | **1x** | 10% in 3 days | 30 days | 2 hours | 3 days | Slack / JIRA ticket |

---

## 4. Error Budget Policy & Operational Rules

1. **Budget Status > 20%:** Normal operations. Feature deployments and schema migrations proceed without gating.
2. **Budget Status between 0% and 20%:** Warning state. High-risk architectural changes require Tech Lead sign-off.
3. **Budget Status < 0% (Exhausted):**
   - **Feature Freeze:** Non-critical product feature releases are halted.
   - **Engineering Shift:** 100% of engineering bandwidth transitions to reliability, performance tuning, and failure mode mitigation.
   - **Mandatory Postmortem:** Blameless postmortem published within 48 hours for any incident contributing >10% of the budget loss.
