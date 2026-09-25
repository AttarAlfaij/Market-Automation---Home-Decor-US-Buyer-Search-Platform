const Tracking = (() => {
  let allBuyers = [];
  let allCampaigns = [];
  let trackingStats = { sent: 0, pending: 0, responses: 0 };
  let currentTab = "activity";
  let leadsAndResponsesSubTab = "responses";
  let allResponses = [];
  let allLeads = [];

  async function render(container) {
    container.innerHTML = `
      <div class="cards-grid" id="trackingStats">
        <div class="stat-card"><div class="spinner"></div></div>
      </div>
      <div class="tab-bar">
        <button class="tab active" data-tab="activity">&#128203; Buyer Activity</button>
        <button class="tab" data-tab="campaigns">&#9993; Campaign Performance</button>
        <button class="tab" data-tab="leads">&#128200; Leads &amp; Responses</button>
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
        else if (currentTab === "leads") renderLeadsAndResponses();
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

  // ── Leads & Responses ──────────────────────────────────────────────

  async function renderLeadsAndResponses() {
    const wrap = document.getElementById("trackingTabContent");
    wrap.innerHTML = `<div class="state-message"><div class="spinner"></div><p>Loading leads &amp; responses...</p></div>`;

    try {
      const [responses, leads, stats] = await Promise.all([
        API.getResponses(),
        API.getLeads(),
        API.getLeadsAndResponsesStats(),
      ]);
      allResponses = responses || [];
      allLeads = leads || [];
      const responseCount = Number(stats.responses) || 0;
      const leadCount = Number(stats.leads) || 0;
      const responseRate = Number(stats.responseRate) || 0;

      wrap.innerHTML = `
        <div class="cards-grid" id="lrStatsCards">
          <div class="stat-card">
            <div class="stat-icon green">&#10003;</div>
            <div class="stat-text">
              <span class="stat-label">Responses</span>
              <span class="stat-value">${responseCount}</span>
            </div>
          </div>
          <div class="stat-card">
            <div class="stat-icon blue">&#9733;</div>
            <div class="stat-text">
              <span class="stat-label">Leads</span>
              <span class="stat-value">${leadCount}</span>
            </div>
          </div>
          <div class="stat-card">
            <div class="stat-icon cyan">&#128200;</div>
            <div class="stat-text">
              <span class="stat-label">Response Rate</span>
              <span class="stat-value">${responseRate}%</span>
            </div>
          </div>
        </div>

        <div class="panel" style="margin-bottom:16px">
          <div class="panel-header lr-header">
            <div class="lr-header-left">
              <h2 id="lrSectionTitle">Responses (${allResponses.length})</h2>
              <div class="lr-subtabs" id="lrSubtabs">
                <button class="lr-subtab active" data-subtab="responses">Responses</button>
                <button class="lr-subtab" data-subtab="leads">Leads</button>
              </div>
            </div>
            <div class="lr-header-actions" id="lrHeaderActions">
            </div>
          </div>
          <div id="lrContent"></div>
        </div>

        <div id="lrLeadFormWrap"></div>
      `;

      document.querySelectorAll("#lrSubtabs .lr-subtab").forEach((btn) => {
        btn.addEventListener("click", () => {
          document.querySelectorAll("#lrSubtabs .lr-subtab").forEach((x) => x.classList.remove("active"));
          btn.classList.add("active");
          leadsAndResponsesSubTab = btn.dataset.subtab;
          renderLeadsAndResponsesContent();
        });
      });

      renderLeadsAndResponsesContent();
    } catch (err) {
      wrap.innerHTML = `
        <div class="panel"><div class="panel-body">
          <div class="state-message error-state">
            <div class="state-icon">&#9888;</div>
            <p>Failed to load leads &amp; responses: ${escapeHtml(err.message)}</p>
          </div>
        </div></div>
      `;
    }
  }

  function renderLeadsAndResponsesContent() {
    if (leadsAndResponsesSubTab === "responses") renderResponsesTab();
    else renderLeadsTab();
  }

  function updateLrStatsCards(stats) {
    const cards = document.getElementById("lrStatsCards");
    if (!cards) return;
    const values = cards.querySelectorAll(".stat-value");
    if (values.length < 3) return;
    values[0].textContent = Number(stats.responses) || 0;
    values[1].textContent = Number(stats.leads) || 0;
    values[2].textContent = (Number(stats.responseRate) || 0) + "%";
  }

  function renderResponsesTab() {
    const contentWrap = document.getElementById("lrContent");
    const titleEl = document.getElementById("lrSectionTitle");
    const actionsEl = document.getElementById("lrHeaderActions");

    if (titleEl) titleEl.textContent = `Responses (${allResponses.length})`;
    if (actionsEl) {
      actionsEl.innerHTML = `
        <button class="btn btn-secondary" id="checkRepliesBtn">&#128270; Check for Replies</button>
        <button class="btn btn-secondary" id="refreshResponsesBtn">&#8635; Refresh</button>
      `;
      document.getElementById("checkRepliesBtn").addEventListener("click", checkForReplies);
      document.getElementById("refreshResponsesBtn").addEventListener("click", renderLeadsAndResponses);
    }

    if (allResponses.length === 0) {
      contentWrap.innerHTML = `
        <div class="panel-body">
          <div class="state-message">
            <div class="state-icon">&#128172;</div>
            <p>No responses detected yet. Buyer replies to campaign emails will appear here automatically.<br>
            You can also click &quot;Check for Replies&quot; to scan your inbox manually.</p>
          </div>
        </div>
      `;
      return;
    }

    let rows = allResponses.map((r) => `
      <tr>
        <td>${escapeHtml(r.buyerName || "-")}</td>
        <td>${escapeHtml(r.companyName || "-")}</td>
        <td>${escapeHtml(r.email || "-")}</td>
        <td>${r.campaignId ? '<a href="#campaigns" class="campaign-link">Campaign #' + escapeHtml(String(r.campaignId)) + '</a>' : "-"}</td>
        <td>${formatDateTime(r.responseDate)}</td>
        <td>${badge(r.responseType || "REPLY")}</td>
        <td class="lr-subject-cell">${escapeHtml(r.subject || "-")}</td>
      </tr>
    `).join("");

    contentWrap.innerHTML = `
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Buyer</th>
              <th>Company</th>
              <th>Email</th>
              <th>Campaign</th>
              <th>Date</th>
              <th>Status</th>
              <th>Subject</th>
            </tr>
          </thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    `;
  }

  function renderLeadsTab() {
    const contentWrap = document.getElementById("lrContent");
    const titleEl = document.getElementById("lrSectionTitle");
    const actionsEl = document.getElementById("lrHeaderActions");

    if (titleEl) titleEl.textContent = `Leads (${allLeads.length})`;
    if (actionsEl) {
      actionsEl.innerHTML = `
        <button class="btn btn-primary" id="addLeadBtn">&#10010; Add Lead</button>
        <button class="btn btn-secondary" id="refreshLeadsBtn">&#8635; Refresh</button>
      `;
      document.getElementById("addLeadBtn").addEventListener("click", showAddLeadForm);
      document.getElementById("refreshLeadsBtn").addEventListener("click", renderLeadsAndResponses);
    }

    if (allLeads.length === 0) {
      contentWrap.innerHTML = `
        <div class="panel-body">
          <div class="state-message">
            <div class="state-icon">&#128200;</div>
            <p>No leads added yet. Click &quot;&#10010; Add Lead&quot; to manually add an interested buyer as a lead.</p>
          </div>
        </div>
      `;
      return;
    }

    let rows = allLeads.map((l) => `
      <tr>
        <td>${escapeHtml(l.buyerName || "-")}</td>
        <td>${escapeHtml(l.companyName || "-")}</td>
        <td>${escapeHtml(l.email || "-")}</td>
        <td>${badge(l.leadStatus)}</td>
        <td class="lr-notes-cell">${escapeHtml(l.notes || "-")}</td>
        <td>${formatDateTime(l.createdAt)}</td>
        <td class="row-actions">
          <button class="btn-icon btn-delete-icon btn-delete-lead" data-id="${l.id}" title="Delete lead">&#10005;</button>
        </td>
      </tr>
    `).join("");

    contentWrap.innerHTML = `
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Buyer Name</th>
              <th>Company</th>
              <th>Email</th>
              <th>Status</th>
              <th>Notes</th>
              <th>Added</th>
              <th></th>
            </tr>
          </thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
    `;

    contentWrap.querySelectorAll(".btn-delete-lead").forEach((btn) => {
      btn.addEventListener("click", () => deleteLead(parseInt(btn.dataset.id)));
    });
  }

  async function checkForReplies() {
    const btn = document.getElementById("checkRepliesBtn");
    if (btn) {
      btn.disabled = true;
      btn.textContent = "Checking...";
    }
    try {
      const result = await API.checkForReplies();
      if (result && result.imapEnabled === false) {
        showToast("IMAP reply detection is not configured. Enable it in application.properties.", "error");
      } else if (result && result.detected !== undefined) {
        showToast(result.message || "Reply check completed", "success");
        if (result.detected > 0) {
          await refreshResponsesAndLeads();
        }
      }
    } catch (err) {
      showToast("Reply check failed: " + err.message, "error");
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.innerHTML = "&#128270; Check for Replies";
      }
    }
  }

  async function refreshResponsesAndLeads() {
    try {
      const [responses, leads, stats] = await Promise.all([
        API.getResponses(),
        API.getLeads(),
        API.getLeadsAndResponsesStats(),
      ]);
      allResponses = responses || [];
      allLeads = leads || [];
      updateLrStatsCards(stats || {});
      renderLeadsAndResponsesContent();
    } catch (_) {}
  }

  function showAddLeadForm() {
    const formWrap = document.getElementById("lrLeadFormWrap");
    if (!formWrap) return;

    if (formWrap.innerHTML.trim()) {
      formWrap.innerHTML = "";
      return;
    }

    formWrap.innerHTML = `
      <div class="panel" style="margin-top:16px">
        <div class="panel-header">
          <h2>Add New Lead</h2>
          <button class="btn btn-secondary" id="closeLeadFormBtn">&#10005; Close</button>
        </div>
        <div class="panel-body">
          <form id="addLeadForm" novalidate>
            <div class="form-row">
              <div class="form-group">
                <label>Buyer Name <span class="required">*</span></label>
                <input type="text" id="lead-buyerName" placeholder="e.g. John Smith">
                <span class="error-msg" id="err-lead-buyerName"></span>
              </div>
              <div class="form-group">
                <label>Company Name</label>
                <input type="text" id="lead-companyName" placeholder="e.g. Global Imports Ltd.">
              </div>
            </div>
            <div class="form-row">
              <div class="form-group">
                <label>Email</label>
                <input type="email" id="lead-email" placeholder="e.g. john@company.com">
                <span class="error-msg" id="err-lead-email"></span>
              </div>
              <div class="form-group">
                <label>Lead Status</label>
                <select id="lead-status">
                  <option value="NEW">New</option>
                  <option value="INTERESTED">Interested</option>
                  <option value="QUALIFIED">Qualified</option>
                  <option value="HOT">Hot</option>
                  <option value="WARM">Warm</option>
                  <option value="COLD">Cold</option>
                  <option value="CONVERTED">Converted</option>
                  <option value="LOST">Lost</option>
                </select>
              </div>
            </div>
            <div class="form-row single">
              <div class="form-group">
                <label>Notes</label>
                <textarea id="lead-notes" rows="3" placeholder="Add any notes about this lead..."></textarea>
              </div>
            </div>
            <div class="form-actions">
              <button type="button" class="btn btn-secondary" id="cancelLeadBtn">Cancel</button>
              <button type="submit" class="btn btn-primary" id="submitLeadBtn">&#10010; Add Lead</button>
            </div>
          </form>
        </div>
      </div>
    `;

    document.getElementById("closeLeadFormBtn").addEventListener("click", () => { formWrap.innerHTML = ""; });
    document.getElementById("cancelLeadBtn").addEventListener("click", () => { formWrap.innerHTML = ""; });
    document.getElementById("addLeadForm").addEventListener("submit", submitLead);
  }

  async function submitLead(e) {
    e.preventDefault();
    const nameInput = document.getElementById("lead-buyerName");
    const emailInput = document.getElementById("lead-email");
    const nameErr = document.getElementById("err-lead-buyerName");
    const emailErr = document.getElementById("err-lead-email");

    nameErr.textContent = "";
    nameErr.style.display = "none";
    emailErr.textContent = "";
    emailErr.style.display = "none";
    nameInput.classList.remove("error");
    emailInput.classList.remove("error");

    const buyerName = nameInput.value.trim();
    if (!buyerName) {
      nameErr.textContent = "Buyer name is required";
      nameErr.style.display = "block";
      nameInput.classList.add("error");
      return;
    }

    const email = emailInput.value.trim();
    if (email && !/^[A-Za-z0-9+_.\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$/.test(email)) {
      emailErr.textContent = "Enter a valid email address";
      emailErr.style.display = "block";
      emailInput.classList.add("error");
      return;
    }

    const btn = document.getElementById("submitLeadBtn");
    btn.disabled = true;
    btn.textContent = "Saving...";

    try {
      await API.addLead({
        buyerName,
        companyName: document.getElementById("lead-companyName").value.trim(),
        email,
        notes: document.getElementById("lead-notes").value.trim(),
        leadStatus: document.getElementById("lead-status").value,
      });
      showToast("Lead added successfully", "success");
      document.getElementById("lrLeadFormWrap").innerHTML = "";
      await refreshResponsesAndLeads();
    } catch (err) {
      showToast("Failed to add lead: " + err.message, "error");
    } finally {
      btn.disabled = false;
      btn.innerHTML = "&#10010; Add Lead";
    }
  }

  async function deleteLead(id) {
    if (!confirm("Delete this lead?")) return;
    try {
      await API.deleteLead(id);
      showToast("Lead deleted", "success");
      await refreshResponsesAndLeads();
    } catch (err) {
      showToast("Failed to delete lead: " + err.message, "error");
    }
  }

  return { render };
})();
