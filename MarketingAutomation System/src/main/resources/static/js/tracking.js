const Tracking = (() => {
  let allBuyers = [];
  let allCampaigns = [];
  let trackingStats = { sent: 0, pending: 0, responses: 0 };
  let currentTab = "activity";
  let emailSummary = null;
  let summaryFilters = { startDate: "", endDate: "" };

  const STATUS_COLOR_PALETTE = ["#2563eb", "#f59e0b", "#0891b2", "#7c3aed", "#db2777", "#64748b"];

  async function render(container) {
    container.innerHTML = `
      <div class="cards-grid" id="trackingStats">
        <div class="stat-card"><div class="spinner"></div></div>
      </div>
      <div class="tab-bar">
        <button class="tab active" data-tab="activity">&#128203; Buyer Activity</button>
        <button class="tab" data-tab="campaigns">&#9993; Campaign Performance</button>
        <button class="tab" data-tab="summary">&#128202; Email Summary</button>
      </div>
      <div id="trackingTabContent">
        <div class="state-message"><div class="spinner"></div><p>Loading tracking data...</p></div>
      </div>
    `;

    container.querySelectorAll(".tab").forEach((t) => {
      t.addEventListener("click", () => {
        container.querySelectorAll(".tab").forEach((x) => x.classList.remove("active"));
        t.classList.add("active");
        currentTab = t.dataset.tab;
        if (currentTab === "activity") renderActivity();
        else if (currentTab === "campaigns") renderCampaignPerformance();
        else if (currentTab === "summary") renderEmailSummary();
      });
    });

    try {
      [allBuyers, allCampaigns, trackingStats] = await Promise.all([
        API.getBuyers(),
        API.getCampaigns(),
        API.getEmailTrackingStats(),
      ]);
      renderStats();
      renderActivity();
    } catch (err) {
      document.getElementById("trackingStats").innerHTML = `
        <div class="stat-card state-message error-state">
          <div class="state-icon">&#9888;</div>
          <p>${escapeHtml(err.message)}</p>
        </div>
      `;
      document.getElementById("trackingTabContent").innerHTML = `
        <div class="panel"><div class="panel-body">
          <div class="state-message error-state"><p>Failed to load tracking data</p></div>
        </div></div>
      `;
    }
  }

  function renderStats() {
    const sent = Number(trackingStats.sent) || 0;
    const pending = Number(trackingStats.pending) || 0;
    const hotLeads = allBuyers.filter((b) => b.classification === "HOT").length;
    const totalCampaigns = allCampaigns.length;

    document.getElementById("trackingStats").innerHTML = `
      <div class="stat-card">
        <div class="stat-icon green">&#10003;</div>
        <div class="stat-text">
          <span class="stat-label">Emails Sent</span>
          <span class="stat-value">${sent}</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber">&#9200;</div>
        <div class="stat-text">
          <span class="stat-label">Pending</span>
          <span class="stat-value">${pending}</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon blue">&#9733;</div>
        <div class="stat-text">
          <span class="stat-label">Hot Leads</span>
          <span class="stat-value">${hotLeads}</span>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon cyan">&#9993;</div>
        <div class="stat-text">
          <span class="stat-label">Campaigns</span>
          <span class="stat-value">${totalCampaigns}</span>
        </div>
      </div>
    `;
  }

  function renderActivity() {
    const wrap = document.getElementById("trackingTabContent");

    const contacted = allBuyers
      .filter((b) => b.emailStatus === "CONTACTED")
      .sort((a, b) => {
        const da = a.contactedAt ? new Date(a.contactedAt).getTime() : 0;
        const db = b.contactedAt ? new Date(b.contactedAt).getTime() : 0;
        return db - da;
      });

    const pending = allBuyers
      .filter((b) => b.emailStatus === "PENDING")
      .sort((a, b) => (a.buyerName || "").localeCompare(b.buyerName || ""));

    const noStatus = allBuyers
      .filter((b) => !b.emailStatus || b.emailStatus === "NOT_CONTACTED")
      .sort((a, b) => (a.buyerName || "").localeCompare(b.buyerName || ""));

    const total = contacted.length + pending.length + noStatus.length;

    if (total === 0) {
      wrap.innerHTML = `
        <div class="panel"><div class="panel-body">
          <div class="state-message">
            <div class="state-icon">&#128203;</div>
            <p>No buyer activity data available yet. Import buyers and send campaigns to see tracking data here.</p>
          </div>
        </div></div>
      `;
      return;
    }

    let rows = "";

    contacted.forEach((b) => {
      rows += `<tr class="activity-row-contacted">
        <td>${escapeHtml(b.buyerName)}</td>
        <td>${escapeHtml(b.email || "-")}</td>
        <td>${escapeHtml(b.companyName || "-")}</td>
        <td>${escapeHtml(b.country || "-")}</td>
        <td>${badge(b.classification)}</td>
        <td><span class="badge badge-sent">SENT</span></td>
        <td>${escapeHtml(b.email || "-")}</td>
        <td>-</td>
        <td>${formatDateTime(b.contactedAt)}</td>
        <td>-</td>
      </tr>`;
    });

    pending.forEach((b) => {
      rows += `<tr class="activity-row-pending">
        <td>${escapeHtml(b.buyerName)}</td>
        <td>${escapeHtml(b.email || "-")}</td>
        <td>${escapeHtml(b.companyName || "-")}</td>
        <td>${escapeHtml(b.country || "-")}</td>
        <td>${badge(b.classification)}</td>
        <td><span class="badge badge-pending">PENDING</span></td>
        <td>-</td>
        <td>-</td>
        <td>-</td>
        <td>-</td>
      </tr>`;
    });

    noStatus.forEach((b) => {
      rows += `<tr class="activity-row-new">
        <td>${escapeHtml(b.buyerName)}</td>
        <td>${escapeHtml(b.email || "-")}</td>
        <td>${escapeHtml(b.companyName || "-")}</td>
        <td>${escapeHtml(b.country || "-")}</td>
        <td>${badge(b.classification)}</td>
        <td><span class="badge badge-default">NEW</span></td>
        <td>-</td>
        <td>-</td>
        <td>-</td>
        <td>-</td>
      </tr>`;
    });

    wrap.innerHTML = `
      <div class="panel">
        <div class="panel-header">
          <h2>Email Activity (${total} buyers)</h2>
          <div class="search-box">
            <input type="text" id="activitySearch" placeholder="Search buyer, email, company..." />
          </div>
        </div>
        <div id="activityTableWrap">
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Buyer</th>
                  <th>Email</th>
                  <th>Company</th>
                  <th>Country</th>
                  <th>Classification</th>
                  <th>Status</th>
                  <th>Sent To</th>
                  <th>Campaign</th>
                  <th>Sent Time</th>
                  <th>Error</th>
                </tr>
              </thead>
              <tbody id="activityBody">
                ${rows}
              </tbody>
            </table>
          </div>
          <div class="pagination">
            <span id="activityCount">Showing ${total} of ${total}</span>
            <div class="pagination-btns">
              <button id="activityPrev" disabled>&laquo; Prev</button>
              <button id="activityNext" disabled>Next &raquo;</button>
            </div>
          </div>
        </div>
      </div>
    `;

    const PAGE_SIZE = 15;
    let activityPage = 1;
    const allRows = document.getElementById("activityBody").querySelectorAll("tr");

    function paginateActivity() {
      const totalRows = allRows.length;
      const totalPages = Math.ceil(totalRows / PAGE_SIZE);
      if (activityPage > totalPages) activityPage = totalPages;
      if (activityPage < 1) activityPage = 1;

      const start = (activityPage - 1) * PAGE_SIZE;
      allRows.forEach((row, i) => {
        row.style.display = i >= start && i < start + PAGE_SIZE ? "" : "none";
      });

      document.getElementById("activityCount").textContent =
        `Showing ${start + 1}\u2013${Math.min(start + PAGE_SIZE, totalRows)} of ${totalRows}`;
      document.getElementById("activityPrev").disabled = activityPage <= 1;
      document.getElementById("activityNext").disabled = activityPage >= totalPages;
    }

    paginateActivity();

    document.getElementById("activityPrev").addEventListener("click", () => {
      activityPage--;
      paginateActivity();
    });

    document.getElementById("activityNext").addEventListener("click", () => {
      activityPage++;
      paginateActivity();
    });

    document.getElementById("activitySearch").addEventListener("input", (e) => {
      const q = e.target.value.toLowerCase().trim();
      let visibleCount = 0;
      allRows.forEach((row) => {
        const text = row.textContent.toLowerCase();
        const show = !q || text.includes(q);
        row.style.display = show ? "" : "none";
        if (show) visibleCount++;
      });
      activityPage = 1;
      const totalPages = Math.ceil(visibleCount / PAGE_SIZE);
      const start = 0;
      let shown = 0;
      allRows.forEach((row) => {
        if (row.style.display !== "none") {
          row.style.display = shown < PAGE_SIZE ? "" : "none";
          shown++;
        }
      });
      document.getElementById("activityCount").textContent =
        `Showing ${Math.min(PAGE_SIZE, visibleCount)} of ${visibleCount}`;
      document.getElementById("activityPrev").disabled = true;
      document.getElementById("activityNext").disabled = visibleCount <= PAGE_SIZE;
    });
  }

  function renderCampaignPerformance() {
    const wrap = document.getElementById("trackingTabContent");

    if (allCampaigns.length === 0) {
      wrap.innerHTML = `
        <div class="panel"><div class="panel-body">
          <div class="state-message">
            <div class="state-icon">&#9993;</div>
            <p>No campaigns created yet. Create a campaign to see performance data.</p>
          </div>
        </div></div>
      `;
      return;
    }

    const sentBuyers = allBuyers.filter((b) => b.emailStatus === "CONTACTED").length;
    const totalBuyers = allBuyers.length;

    let rows = "";
    allCampaigns.forEach((c) => {
      const sentPct = totalBuyers > 0 ? Math.round((sentBuyers / totalBuyers) * 100) : 0;
      const limit = c.sendingLimit != null ? c.sendingLimit : "Unlimited";
      rows += `
        <tr>
          <td><a href="#campaigns" class="campaign-link">${escapeHtml(c.campaignName)}</a></td>
          <td>${escapeHtml(c.emailSubject || "-")}</td>
          <td>${escapeHtml(c.targetAudience || "-")}</td>
          <td>${limit}</td>
          <td>${escapeHtml(c.senderName || "-")}</td>
          <td>${escapeHtml(c.senderEmail || "-")}</td>
          <td>${c.attachmentName ? '<span class="badge badge-sent">&#128206; Yes</span>' : '<span class="badge badge-default">None</span>'}</td>
          <td>${formatDate(c.createdAt)}</td>
        </tr>
      `;
    });

    wrap.innerHTML = `
      <div class="panel">
        <div class="panel-header">
          <h2>${allCampaigns.length} Campaign${allCampaigns.length !== 1 ? "s" : ""}</h2>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Campaign</th>
                <th>Subject</th>
                <th>Target Audience</th>
                <th>Limit</th>
                <th>Sender</th>
                <th>Sender Email</th>
                <th>Attachment</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              ${rows}
            </tbody>
          </table>
        </div>
      </div>
    `;
  }

  // ── Email Summary (date range + status breakdown) ──────────────────

  async function renderEmailSummary() {
    const wrap = document.getElementById("trackingTabContent");
    wrap.innerHTML = `
      <div class="panel" style="margin-bottom:16px">
        <div class="panel-header">
          <h2>Filter Sent Emails by Date</h2>
        </div>
        <div class="panel-body">
          <div class="form-row summary-filters">
            <div class="form-group">
              <label>From Date</label>
              <input type="date" id="summaryStartDate" value="${escapeHtml(summaryFilters.startDate)}">
            </div>
            <div class="form-group">
              <label>To Date</label>
              <input type="date" id="summaryEndDate" value="${escapeHtml(summaryFilters.endDate)}">
            </div>
            <div class="form-group summary-filter-actions">
              <button class="btn btn-primary" id="summaryApplyBtn">Apply</button>
              <button class="btn btn-secondary" id="summaryResetBtn">All Time</button>
            </div>
          </div>
        </div>
      </div>
      <div id="summaryContent">
        <div class="state-message"><div class="spinner"></div><p>Loading email summary...</p></div>
      </div>
    `;

    document.getElementById("summaryApplyBtn").addEventListener("click", applySummaryFilters);
    document.getElementById("summaryResetBtn").addEventListener("click", resetSummaryFilters);
    ["summaryStartDate", "summaryEndDate"].forEach((id) => {
      document.getElementById(id).addEventListener("keydown", (e) => {
        if (e.key === "Enter") applySummaryFilters();
      });
    });

    await loadEmailSummary();
  }

  function applySummaryFilters() {
    const start = document.getElementById("summaryStartDate").value;
    const end = document.getElementById("summaryEndDate").value;

    if ((start && !end) || (!start && end)) {
      showToast("Select both a start date and an end date", "error");
      return;
    }
    if (start && end && start > end) {
      showToast("Start date must be on or before end date", "error");
      return;
    }

    summaryFilters = { startDate: start, endDate: end };
    loadEmailSummary();
  }

  function resetSummaryFilters() {
    summaryFilters = { startDate: "", endDate: "" };
    const startEl = document.getElementById("summaryStartDate");
    const endEl = document.getElementById("summaryEndDate");
    if (startEl) startEl.value = "";
    if (endEl) endEl.value = "";
    loadEmailSummary();
  }

  async function loadEmailSummary() {
    const content = document.getElementById("summaryContent");
    if (!content) return;

    content.innerHTML = `<div class="state-message"><div class="spinner"></div><p>Loading email summary...</p></div>`;

    try {
      emailSummary = await API.getEmailSummary(summaryFilters.startDate, summaryFilters.endDate);
      renderSummaryContent();
    } catch (err) {
      content.innerHTML = `
        <div class="panel"><div class="panel-body">
          <div class="state-message error-state">
            <div class="state-icon">&#9888;</div>
            <p>Failed to load email summary: ${escapeHtml(err.message)}</p>
          </div>
        </div></div>
      `;
    }
  }

  function renderSummaryContent() {
    const content = document.getElementById("summaryContent");
    if (!content) return;

    const summary = emailSummary || {};
    const total = Number(summary.total) || 0;
    const counts = Array.isArray(summary.statusCounts) ? summary.statusCounts : [];
    const successful = Number(summary.successful) || 0;
    const failed = Number(summary.failed) || 0;
    const successRate = total > 0 ? Math.round((successful * 100) / total) : 0;
    const rangeLabel = summaryFilters.startDate && summaryFilters.endDate
      ? `${formatDate(summaryFilters.startDate)} \u2013 ${formatDate(summaryFilters.endDate)}`
      : "All time";

    const cards = `
      <div class="cards-grid" id="summaryStats">
        <div class="stat-card">
          <div class="stat-icon cyan">&#9993;</div>
          <div class="stat-text">
            <span class="stat-label">Total Emails</span>
            <span class="stat-value">${total}</span>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon green">&#10003;</div>
          <div class="stat-text">
            <span class="stat-label">Sent</span>
            <span class="stat-value">${successful}</span>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon amber">&#9888;</div>
          <div class="stat-text">
            <span class="stat-label">Failed</span>
            <span class="stat-value">${failed}</span>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon blue">&#128200;</div>
          <div class="stat-text">
            <span class="stat-label">Success Rate</span>
            <span class="stat-value">${successRate}%</span>
          </div>
        </div>
      </div>
    `;

    if (total === 0) {
      content.innerHTML = cards + `
        <div class="panel"><div class="panel-body">
          <div class="state-message">
            <div class="state-icon">&#128202;</div>
            <p>No emails recorded for ${escapeHtml(rangeLabel)}. Send a campaign or widen the date range.</p>
          </div>
        </div></div>
      `;
      return;
    }

    const legend = counts.map((c) => {
      const count = Number(c.count) || 0;
      const pct = Number(c.percentage) || 0;
      return `<li class="legend-item">
        <span class="legend-dot" style="background:${statusColor(c.status)}"></span>
        <span class="legend-label">${escapeHtml(c.status)}</span>
        <span class="legend-value">${count} <span class="legend-pct">(${pct}%)</span></span>
      </li>`;
    }).join("");

    content.innerHTML = cards + `
      <div class="panel">
        <div class="panel-header">
          <h2>Status Breakdown \u2014 ${escapeHtml(rangeLabel)}</h2>
        </div>
        <div class="panel-body">
          <div class="donut-wrap">
            ${donutChart(counts, total)}
            <ul class="donut-legend">${legend}</ul>
          </div>
        </div>
      </div>
    `;
  }

  function donutChart(counts, total) {
    let start = 0;
    const segments = counts.map((c) => {
      const count = Number(c.count) || 0;
      const end = start + (total > 0 ? (count * 100) / total : 0);
      const segment = `${statusColor(c.status)} ${start.toFixed(2)}% ${end.toFixed(2)}%`;
      start = end;
      return segment;
    });

    return `
      <div class="donut-chart" style="background: conic-gradient(${segments.join(", ")})">
        <div class="donut-hole">
          <span class="donut-total">${total}</span>
          <span class="donut-caption">Emails</span>
        </div>
      </div>
    `;
  }

  function statusColor(status) {
    const value = String(status || "").toUpperCase();
    if (value === "SENT") return "var(--color-success)";
    if (value === "FAILED") return "var(--color-danger)";
    if (value === "PENDING") return "var(--color-warning)";
    if (value === "SKIPPED") return "#eab308";
    let hash = 0;
    for (let i = 0; i < value.length; i++) {
      hash = (hash * 31 + value.charCodeAt(i)) % 997;
    }
    return STATUS_COLOR_PALETTE[hash % STATUS_COLOR_PALETTE.length];
  }

  return { render };
})();