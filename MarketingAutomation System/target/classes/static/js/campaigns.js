const Campaigns = (() => {
  let allCampaigns = [];
  let filtered = [];
  let view = "list";
  let editingId = null;
  let currentCampaign = null;
  let currentRecipients = [];
  let removedRecipientIds = new Set();
  let selectedRecipientIds = new Set();
  let bulkDeleteInProgress = false;
  let sendPollTimer = null;

  const EMAIL_RE = /^[A-Za-z0-9+_.\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$/;

  async function render(container) {
    view = "list";
    editingId = null;
    container.innerHTML = `
      <div class="tab-bar">
        <button class="tab active" data-tab="list">&#128203; All Campaigns</button>
        <button class="tab" data-tab="create">&#10010; Create Campaign</button>
      </div>
      <div id="campaignTabContent">
        <div class="state-message"><div class="spinner"></div><p>Loading campaigns...</p></div>
      </div>
    `;

    container.querySelectorAll(".tab").forEach((t) => {
      t.addEventListener("click", () => {
        const tab = t.dataset.tab;
        container.querySelectorAll(".tab").forEach((x) => x.classList.remove("active"));
        t.classList.add("active");
        if (tab === "list") {
          view = "list";
          editingId = null;
          showList();
        } else {
          view = "create";
          editingId = null;
          showCreateForm();
        }
      });
    });

    try {
      allCampaigns = await API.getCampaigns();
      filtered = allCampaigns;
      showList();
    } catch (err) {
      document.getElementById("campaignTabContent").innerHTML = `
        <div class="panel"><div class="panel-body">
          <div class="state-message error-state">
            <div class="state-icon">&#9888;</div>
            <p>${escapeHtml(err.message)}</p>
          </div>
        </div></div>
      `;
    }
  }

  function showList() {
    const wrap = document.getElementById("campaignTabContent");
    wrap.innerHTML = `
      <div class="panel">
        <div class="panel-header">
          <h2>${filtered.length} Campaign${filtered.length !== 1 ? "s" : ""}</h2>
          <div class="search-box">
            <input type="text" id="campaignSearch" placeholder="Search campaigns..." />
          </div>
        </div>
        <div id="campaignTableWrap"></div>
      </div>
    `;
    document.getElementById("campaignSearch").addEventListener("input", onSearch);
    renderTable();
  }

  function onSearch(e) {
    const q = e.target.value.toLowerCase().trim();
    if (!q) {
      filtered = allCampaigns;
    } else {
      filtered = allCampaigns.filter((c) =>
        [c.campaignName, c.emailSubject, c.senderName, c.senderCompany, c.senderEmail, c.targetAudience, c.productName, c.productCategory]
          .filter(Boolean)
          .some((v) => v.toLowerCase().includes(q))
      );
    }
    renderTable();
  }

  function renderTable() {
    const wrap = document.getElementById("campaignTableWrap");

    if (filtered.length === 0) {
      wrap.innerHTML = `
        <div class="state-message">
          <div class="state-icon">&#128269;</div>
          <p>${allCampaigns.length === 0 ? "No campaigns yet. Create your first campaign." : "No campaigns match your search."}</p>
        </div>
      `;
      return;
    }

    wrap.innerHTML = `
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Campaign</th>
              <th>Subject</th>
              <th>Target</th>
              <th>Sender</th>
              <th>Limit</th>
              <th>Product</th>
              <th>Attachment</th>
              <th>Created</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            ${filtered.map((c) => `
              <tr>
                <td><a href="#" class="campaign-link" data-id="${c.id}">${escapeHtml(c.campaignName)}</a></td>
                <td>${escapeHtml(c.emailSubject)}</td>
                <td>${badge(c.targetAudience)}</td>
                <td>${escapeHtml(c.senderName)}</td>
                <td>${c.sendingLimit != null ? c.sendingLimit : "-"}</td>
                <td>${escapeHtml(c.productName || "-")}</td>
                <td>${c.attachmentName ? '<span class="badge badge-sent">&#128206; ' + escapeHtml(c.attachmentName) + "</span>" : "-"}</td>
                <td>${formatDate(c.createdAt)}</td>
                <td class="row-actions">
                  <button class="btn-icon btn-edit" data-id="${c.id}" title="Edit">&#9998;</button>
                  <button class="btn-icon btn-delete-icon" data-id="${c.id}" title="Delete">&#10005;</button>
                </td>
              </tr>
            `).join("")}
          </tbody>
        </table>
      </div>
    `;

    wrap.querySelectorAll(".campaign-link").forEach((a) => {
      a.addEventListener("click", (e) => {
        e.preventDefault();
        showDetail(parseInt(a.dataset.id));
      });
    });

    wrap.querySelectorAll(".btn-edit").forEach((btn) => {
      btn.addEventListener("click", () => startEdit(parseInt(btn.dataset.id)));
    });

    wrap.querySelectorAll(".btn-delete-icon").forEach((btn) => {
      btn.addEventListener("click", () => deleteCampaign(parseInt(btn.dataset.id)));
    });
  }

  async function showDetail(id) {
    const wrap = document.getElementById("campaignTabContent");
    wrap.innerHTML = `<div class="state-message"><div class="spinner"></div><p>Loading campaign...</p></div>`;

    try {
      const c = await API.getCampaign(id);
      currentCampaign = c;
      removedRecipientIds = new Set();
      selectedRecipientIds = new Set();
      renderDetail(c);

      try {
        currentRecipients = await API.getCampaignRecipients(id);
        currentRecipients.forEach((r) => { r.savedEmail = r.email || ""; });
      } catch (recErr) {
        currentRecipients = [];
        const recWrap = document.getElementById("recipientsWrap");
        if (recWrap) {
          recWrap.innerHTML = `
            <div class="state-message error-state">
              <div class="state-icon">&#9888;</div>
              <p>Could not load recipients: ${escapeHtml(recErr.message)}</p>
            </div>
          `;
        }
        showToast("Failed to load recipients: " + recErr.message, "error");
      }
      renderRecipients();
      resumeSendStatusCheck();
    } catch (err) {
      wrap.innerHTML = `
        <div class="panel"><div class="panel-body">
          <div class="state-message error-state">
            <div class="state-icon">&#9888;</div>
            <p>${escapeHtml(err.message)}</p>
            <button class="btn btn-secondary" id="detailBackBtn">Back to list</button>
          </div>
        </div></div>
      `;
      document.getElementById("detailBackBtn").addEventListener("click", () => {
        view = "list";
        showList();
      });
    }
  }

  function renderDetail(c) {
    const wrap = document.getElementById("campaignTabContent");
    wrap.innerHTML = `
      <div class="panel" style="margin-bottom:16px">
        <div class="panel-header">
          <h2>${escapeHtml(c.campaignName)}</h2>
          <div class="detail-actions">
            <button class="btn btn-secondary" id="detailBackBtn">&#8592; Back</button>
            <button class="btn btn-secondary" id="detailEditBtn">Edit</button>
            <button class="btn btn-danger" id="detailDeleteBtn">Delete</button>
            <button class="btn btn-primary" id="detailSendBtn">&#10148; Send Campaign</button>
          </div>
        </div>
        <div class="panel-body">
          <div class="detail-grid">
            <div class="detail-section">
              <h3>Email Content</h3>
              <div class="detail-row"><span class="detail-label">Subject</span><span class="detail-value">${escapeHtml(c.emailSubject)}</span></div>
              <div class="detail-row"><span class="detail-label">Body</span><span class="detail-value detail-body">${/<[a-z!/][^>]*>/i.test(c.emailBody || "")
                ? `<iframe id="detailBodyFrame" sandbox="" style="border:1px solid var(--color-border);border-radius:6px;width:100%;min-height:420px;background:#ffffff;" title="Email body preview"></iframe>`
                : escapeHtml(c.emailBody)}</span></div>
              <div class="detail-row"><span class="detail-label">Target Audience</span><span class="detail-value">${badge(c.targetAudience)}</span></div>
              <div class="detail-row"><span class="detail-label">Sending Limit</span><span class="detail-value">${c.sendingLimit != null ? c.sendingLimit : "-"}</span></div>
              <div class="detail-row"><span class="detail-label">Delay (seconds)</span><span class="detail-value">${c.delayBetweenEmails != null ? c.delayBetweenEmails : "-"}</span></div>
            </div>
            <div class="detail-section">
              <h3>Product Information</h3>
              <div class="detail-row"><span class="detail-label">Product Name</span><span class="detail-value">${escapeHtml(c.productName || "-")}</span></div>
              <div class="detail-row"><span class="detail-label">Category</span><span class="detail-value">${escapeHtml(c.productCategory || "-")}</span></div>
              <div class="detail-row"><span class="detail-label">Export Availability</span><span class="detail-value">${badge(c.exportAvailability)}</span></div>
              <div class="detail-row"><span class="detail-label">Shipping</span><span class="detail-value">${badge(c.shippingAvailability)}</span></div>
              <div class="detail-row"><span class="detail-label">MOQ</span><span class="detail-value">${escapeHtml(c.minimumOrderQuantity || "-")}</span></div>
              <div class="detail-row"><span class="detail-label">Customization</span><span class="detail-value">${escapeHtml(c.customizationOptions || "-")}</span></div>
            </div>
            <div class="detail-section">
              <h3>Sender Details</h3>
              <div class="detail-row"><span class="detail-label">Name</span><span class="detail-value">${escapeHtml(c.senderName)}</span></div>
              <div class="detail-row"><span class="detail-label">Company</span><span class="detail-value">${escapeHtml(c.senderCompany)}</span></div>
              <div class="detail-row"><span class="detail-label">Email</span><span class="detail-value">${escapeHtml(c.senderEmail)}</span></div>
              <div class="detail-row"><span class="detail-label">Phone</span><span class="detail-value">${escapeHtml(c.senderPhone || "-")}</span></div>
            </div>
            <div class="detail-section">
              <h3>Attachment</h3>
              <div class="detail-row"><span class="detail-label">File</span><span class="detail-value">${c.attachmentName ? escapeHtml(c.attachmentName) : "No attachment"}</span></div>
            </div>
            <div class="detail-section">
              <h3>Metadata</h3>
              <div class="detail-row"><span class="detail-label">ID</span><span class="detail-value">${c.id}</span></div>
              <div class="detail-row"><span class="detail-label">Created</span><span class="detail-value">${formatDate(c.createdAt)}</span></div>
            </div>
          </div>
        </div>
      </div>

      <div class="panel" style="margin-bottom:16px">
        <div class="panel-header">
          <h2>Review Recipients (<span id="recipientCount">0</span>)</h2>
          <div class="recipients-actions">
            <button class="btn btn-secondary" id="addRecipientBtn">&#10010; Add Recipient</button>
            <button class="btn btn-secondary" id="removeSentBtn">&#10005; Remove Sent Buyers</button>
            <button class="btn btn-secondary" id="removeEmptyEmailBtn">&#10005; Remove Empty Email Buyers</button>
            <button class="btn btn-danger" id="removeContactedBtn">&#128465; Remove All Contacted Buyers</button>
            <button class="btn btn-danger" id="removeAllRecipientsBtn">&#10005; Remove All</button>
            <span class="selection-count" id="selectionCount">0 buyers selected</span>
            <button class="btn btn-danger" id="deleteSelectedBtn" disabled>&#128465; Delete Selected</button>
          </div>
          <span class="recipients-hint">Edit or remove recipients before sending. Nothing is sent until you click &quot;Send Campaign&quot;.</span>
        </div>
        <div id="addRecipientFormWrap"></div>
        <div id="recipientsWrap"></div>
      </div>

      <div id="sendResultWrap"></div>
    `;

    document.getElementById("detailBackBtn").addEventListener("click", () => {
      stopSendPolling();
      view = "list";
      showList();
    });
    const bodyFrame = document.getElementById("detailBodyFrame");
    if (bodyFrame) bodyFrame.srcdoc = `<!DOCTYPE html><html><head><meta charset="utf-8"></head><body style="margin:0;background:#ffffff;">${currentCampaign.emailBody}</body></html>`;
    document.getElementById("detailEditBtn").addEventListener("click", () => startEdit(c.id));
    document.getElementById("detailDeleteBtn").addEventListener("click", () => deleteCampaign(c.id));
    document.getElementById("detailSendBtn").addEventListener("click", () => sendCampaignFromFrontend());
    document.getElementById("addRecipientBtn").addEventListener("click", () => toggleAddRecipientForm());
    document.getElementById("removeSentBtn").addEventListener("click", () => removeSentBuyers());
    document.getElementById("removeEmptyEmailBtn").addEventListener("click", () => removeEmptyEmailBuyers());
    document.getElementById("removeContactedBtn").addEventListener("click", () => removeContactedBuyers());
    document.getElementById("removeAllRecipientsBtn").addEventListener("click", () => removeAllRecipients());
    document.getElementById("deleteSelectedBtn").addEventListener("click", deleteSelectedRecipients);

    renderRecipients();
  }

  function activeRecipients() {
    return currentRecipients.filter((r) => !removedRecipientIds.has(r.buyerId));
  }

  function hasValidRecipientEmail(r) {
    return EMAIL_RE.test((r.email || "").trim());
  }

  function sourceHost(url) {
    try { return new URL(url).hostname.replace(/^www\./, ""); } catch (_) { return url; }
  }

  function labelFromUrl(url) {
    const host = sourceHost(url).toLowerCase();
    if (/linkedin\.com$/i.test(host)) return "LinkedIn";
    if (/facebook\.com$/i.test(host)) return "Facebook";
    return "Google Search";
  }

  function recipientSource(r) {
    const candidates = [
      r.sourceUrl,
      r.website,
    ];
    for (const raw of candidates) {
      const url = (raw || "").trim();
      if (/^https?:\/\//i.test(url)) return { url, label: labelFromUrl(url) };
    }
    return null;
  }

  function recipientSourceCell(r) {
    const src = recipientSource(r);
    if (!src) return "-";
    return `<a class="recipient-source-link" href="${escapeHtml(src.url)}" target="_blank" rel="noopener" title="${escapeHtml(src.url)}">${escapeHtml(src.label)}</a>`;
  }

  function renderRecipients() {
    const wrap = document.getElementById("recipientsWrap");
    if (!wrap) return;

    const list = activeRecipients();
    const countEl = document.getElementById("recipientCount");
    if (countEl) countEl.textContent = list.length;

    const displayedIds = new Set(list.map((r) => r.buyerId));
    selectedRecipientIds.forEach((id) => {
      if (!displayedIds.has(id)) selectedRecipientIds.delete(id);
    });
    updateBulkSelectionUI();

    if (list.length === 0) {
      wrap.innerHTML = `
        <div class="state-message">
          <div class="state-icon">&#128100;</div>
          <p>No recipients in the send list. Click &quot;&#10010; Add Recipient&quot; to add one manually.</p>
        </div>
      `;
      return;
    }

    const ordered = list
      .map((r, i) => ({ r, i }))
      .sort((a, b) =>
        (Number(hasValidRecipientEmail(b.r)) - Number(hasValidRecipientEmail(a.r))) || (a.i - b.i)
      )
      .map((x) => x.r);

    wrap.innerHTML = `
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th class="recipient-select-col">
                <input type="checkbox" id="selectAllRecipients" title="Select all buyers" />
              </th>
              <th>Buyer</th>
              <th>Company</th>
              <th>Country</th>
              <th>Source / Website</th>
              <th>Email</th>
              <th>Status</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            ${ordered.map((r) => `
              <tr data-buyer-id="${r.buyerId}">
                <td class="recipient-select-col">
                  <input type="checkbox" class="recipient-checkbox" data-id="${r.buyerId}"
                         ${selectedRecipientIds.has(r.buyerId) ? "checked" : ""} title="Select ${escapeHtml(r.buyerName || "buyer")}" />
                </td>
                <td>${escapeHtml(r.buyerName || "-")}</td>
                <td>${escapeHtml(r.companyName || "-")}</td>
                <td>${escapeHtml(r.country || "-")}</td>
                <td>${recipientSourceCell(r)}</td>
                <td>
                  <input type="email" class="recipient-email-input" id="recipient-email-${r.buyerId}"
                         value="${escapeHtml(r.email || "")}" placeholder="${r.email ? "" : "No email on file - add one to include this buyer..."}" />
                  <span class="error-msg recipient-error" id="recipient-err-${r.buyerId}"></span>
                </td>
                <td>${r.alreadySent ? badge("SENT") : badge(r.emailStatus || (r.email ? "PENDING" : "NO EMAIL"))}</td>
                <td class="row-actions">
                  <button class="btn-icon btn-save-recipient" data-id="${r.buyerId}" title="Save email">&#128190;</button>
                  <button class="btn-icon btn-delete-icon btn-delete-recipient" data-id="${r.buyerId}" title="Delete recipient permanently from this campaign">&#128465;</button>
                  <button class="btn-icon btn-delete-icon btn-remove-recipient" data-id="${r.buyerId}" title="Remove recipient">&#10005;</button>
                </td>
              </tr>
            `).join("")}
          </tbody>
        </table>
      </div>
    `;

    wrap.querySelectorAll(".btn-save-recipient").forEach((btn) => {
      btn.addEventListener("click", () => saveRecipientEmail(parseInt(btn.dataset.id)));
    });
    const selectAllBox = document.getElementById("selectAllRecipients");
    if (selectAllBox) {
      selectAllBox.addEventListener("change", () => toggleSelectAll(selectAllBox.checked));
    }
    wrap.querySelectorAll(".recipient-checkbox").forEach((box) => {
      box.addEventListener("change", () => {
        const id = Number(box.dataset.id);
        if (box.checked) {
          selectedRecipientIds.add(id);
        } else {
          selectedRecipientIds.delete(id);
        }
        updateBulkSelectionUI();
      });
    });
    wrap.querySelectorAll(".btn-remove-recipient").forEach((btn) => {
      btn.addEventListener("click", () => removeRecipient(parseInt(btn.dataset.id)));
    });
    wrap.querySelectorAll(".btn-delete-recipient").forEach((btn) => {
      btn.addEventListener("click", () => deleteRecipient(parseInt(btn.dataset.id)));
    });
    wrap.querySelectorAll(".recipient-email-input").forEach((input) => {
      input.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          saveRecipientEmail(parseInt(input.id.replace("recipient-email-", "")));
        }
      });
    });
    updateBulkSelectionUI();
  }

  function updateBulkSelectionUI() {
    const displayedIds = activeRecipients().map((r) => r.buyerId);
    const selectedDisplayed = displayedIds.filter((id) => selectedRecipientIds.has(id)).length;

    const countEl = document.getElementById("selectionCount");
    if (countEl) {
      countEl.textContent = `${selectedRecipientIds.size} buyer${selectedRecipientIds.size === 1 ? "" : "s"} selected`;
    }

    const deleteBtn = document.getElementById("deleteSelectedBtn");
    if (deleteBtn) {
      deleteBtn.disabled = bulkDeleteInProgress || selectedRecipientIds.size === 0;
    }

    const selectAllBox = document.getElementById("selectAllRecipients");
    if (selectAllBox) {
      selectAllBox.disabled = displayedIds.length === 0;
      selectAllBox.checked = displayedIds.length > 0 && selectedDisplayed === displayedIds.length;
      selectAllBox.indeterminate = selectedDisplayed > 0 && selectedDisplayed < displayedIds.length;
    }
  }

  function toggleSelectAll(checked) {
    const list = activeRecipients();
    if (checked) {
      list.forEach((r) => selectedRecipientIds.add(r.buyerId));
    } else {
      list.forEach((r) => selectedRecipientIds.delete(r.buyerId));
    }
    const wrap = document.getElementById("recipientsWrap");
    if (wrap) {
      wrap.querySelectorAll(".recipient-checkbox").forEach((box) => { box.checked = checked; });
    }
    updateBulkSelectionUI();
  }

  async function deleteSelectedRecipients() {
    if (bulkDeleteInProgress) return;

    const ids = [...selectedRecipientIds];
    if (ids.length === 0) {
      showToast("No buyers selected. Select at least one buyer to delete.", "error");
      return;
    }
    const label = `selected buyer${ids.length === 1 ? "" : "s"}`;
    if (!confirm(`Are you sure you want to delete ${ids.length} ${label}?`)) return;

    bulkDeleteInProgress = true;
    const deleteBtn = document.getElementById("deleteSelectedBtn");
    if (deleteBtn) {
      deleteBtn.disabled = true;
      deleteBtn.textContent = "Deleting...";
    }

    let deleted = false;
    try {
      await API.deleteRecipients(currentCampaign.id, ids);
      const removed = new Set(ids);
      currentRecipients = currentRecipients.filter((x) => !removed.has(x.buyerId));
      selectedRecipientIds = new Set();
      deleted = true;
      showToast(`${ids.length} ${label} deleted from this campaign`, "success");
    } catch (err) {
      showToast("Error deleting selected buyers: " + err.message, "error");
    } finally {
      bulkDeleteInProgress = false;
      if (deleteBtn) deleteBtn.innerHTML = "&#128465; Delete Selected";
    }

    if (deleted) {
      try {
        currentRecipients = await API.getCampaignRecipients(currentCampaign.id);
        currentRecipients.forEach((r) => { r.savedEmail = r.email || ""; });
      } catch (recErr) {
        showToast("Could not refresh recipients: " + recErr.message, "error");
      }
    }
    renderRecipients();
  }

  function setRecipientError(buyerId, msg) {
    const input = document.getElementById("recipient-email-" + buyerId);
    const err = document.getElementById("recipient-err-" + buyerId);
    if (input) input.classList.toggle("error", !!msg);
    if (err) {
      err.textContent = msg || "";
      err.style.display = msg ? "block" : "none";
    }
  }

  async function saveRecipientEmail(buyerId) {
    const input = document.getElementById("recipient-email-" + buyerId);
    if (!input) return;
    const email = input.value.trim().toLowerCase();

    if (!email) {
      setRecipientError(buyerId, "Email is required");
      return;
    }
    if (!EMAIL_RE.test(email)) {
      setRecipientError(buyerId, "Enter a valid email address");
      return;
    }
    const duplicate = activeRecipients().some((r) => r.buyerId !== buyerId && (r.email || "").trim().toLowerCase() === email);
    if (duplicate) {
      setRecipientError(buyerId, "Another recipient already uses this email");
      return;
    }

    try {
      const updated = await API.updateRecipientEmail(currentCampaign.id, buyerId, email);
      const local = currentRecipients.find((r) => r.buyerId === buyerId);
      if (local) {
        const savedEmail = updated.data && updated.data.email ? updated.data.email : email;
        local.email = savedEmail;
        local.savedEmail = savedEmail;
      }
      setRecipientError(buyerId, "");
      renderRecipients();
      showToast("Recipient email updated", "success");
    } catch (err) {
      setRecipientError(buyerId, err.message);
    }
  }

  function removeRecipient(buyerId) {
    const r = currentRecipients.find((x) => x.buyerId === buyerId);
    if (!r) return;

    removedRecipientIds.add(buyerId);
    renderRecipients();
    showToast(`Removed ${r.email || r.buyerName || "recipient"} from send list`, "success");
  }

  async function deleteRecipient(buyerId) {
    const r = currentRecipients.find((x) => x.buyerId === buyerId);
    if (!r) return;

    if (!confirm("Are you sure you want to permanently delete this buyer from this campaign?")) return;

    try {
      await API.deleteRecipient(currentCampaign.id, buyerId);
      currentRecipients = currentRecipients.filter((x) => x.buyerId !== buyerId);
      removedRecipientIds.delete(buyerId);
      renderRecipients();
      showToast(`Buyer ${r.buyerName || r.email || "deleted"} permanently deleted from this campaign`, "success");
    } catch (err) {
      showToast("Error deleting recipient: " + err.message, "error");
    }
  }

  function removeAllRecipients() {
    const list = activeRecipients();
    if (list.length === 0) {
      showToast("No recipients to remove", "error");
      return;
    }
    if (!confirm(`Remove all ${list.length} recipient(s) from the send list?`)) return;

    list.forEach((r) => removedRecipientIds.add(r.buyerId));
    renderRecipients();
    showToast(`Removed all ${list.length} recipient(s) from send list`, "success");
  }

  // Removes every recipient that has already been sent an email, keeping only
  // PENDING recipients in the current campaign's send list. This only affects
  // the on-screen recipient list - it never deletes the buyers from the main
  // buyer database.
  function removeSentBuyers() {
    const sent = activeRecipients().filter((r) => r.alreadySent);
    if (sent.length === 0) {
      showToast("No sent buyers to remove - all recipients are pending", "success");
      return;
    }
    if (!confirm(`Remove ${sent.length} sent buyer(s) from the send list? ${sent.length} PENDING recipient(s) will be kept.`)) return;

    sent.forEach((r) => removedRecipientIds.add(r.buyerId));
    renderRecipients();
    showToast(`Removed ${sent.length} sent buyer(s). ${activeRecipients().length} PENDING recipient(s) remain.`, "success");
  }

  // Removes every recipient that has no email on file, keeping only recipients
  // with a valid email in the current campaign's send list. This only affects
  // the on-screen recipient list - it never deletes the buyers from the main
  // buyer database.
  function removeEmptyEmailBuyers() {
    const noEmail = activeRecipients().filter((r) => !hasValidRecipientEmail(r));
    if (noEmail.length === 0) {
      showToast("No empty email buyers to remove - all recipients have a valid email", "success");
      return;
    }
    if (!confirm(`Remove ${noEmail.length} buyer(s) with no email from the send list? ${activeRecipients().length - noEmail.length} recipient(s) with email will be kept.`)) return;

    noEmail.forEach((r) => removedRecipientIds.add(r.buyerId));
    renderRecipients();
    showToast(`Removed ${noEmail.length} empty email buyer(s). ${activeRecipients().length} recipient(s) with email remain.`, "success");
  }

  // Removes every recipient whose status is CONTACTED (only the recipients
  // that display the "CONTACTED" badge), keeping SENT and PENDING recipients
  // in the current campaign's send list. This only affects the on-screen
  // recipient list - it never deletes the buyers from the main buyer database.
  function removeContactedBuyers() {
    const contacted = activeRecipients().filter((r) => r.emailStatus === "CONTACTED");
    if (contacted.length === 0) {
      showToast("No contacted buyers to remove - all recipients are pending", "success");
      return;
    }
    if (!confirm(`Remove all ${contacted.length} contacted buyer(s) from the send list? ${activeRecipients().length - contacted.length} PENDING recipient(s) will be kept. This will not delete buyers from the main database.`)) return;

    contacted.forEach((r) => removedRecipientIds.add(r.buyerId));
    renderRecipients();
    showToast(`Removed all ${contacted.length} contacted buyer(s). ${activeRecipients().length} PENDING recipient(s) remain.`, "success");
  }

  function toggleAddRecipientForm() {
    const wrap = document.getElementById("addRecipientFormWrap");
    if (!wrap) return;

    if (wrap.innerHTML.trim()) {
      wrap.innerHTML = "";
      return;
    }

    wrap.innerHTML = `
      <div class="panel-body add-recipient-body">
        <form id="addRecipientForm" novalidate>
          <div class="form-row">
            <div class="form-group">
              <label>Buyer Name <span class="required">*</span></label>
              <input type="text" id="ar-buyerName" placeholder="e.g. John Smith">
              <span class="error-msg" id="err-ar-buyerName"></span>
            </div>
            <div class="form-group">
              <label>Company Name <span class="required">*</span></label>
              <input type="text" id="ar-companyName" placeholder="e.g. Global Imports Ltd.">
              <span class="error-msg" id="err-ar-companyName"></span>
            </div>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>Email <span class="required">*</span></label>
              <input type="email" id="ar-email" placeholder="e.g. john@company.com">
              <span class="error-msg" id="err-ar-email"></span>
            </div>
            <div class="form-group">
              <label>Country <span class="required">*</span></label>
              <input type="text" id="ar-country" placeholder="e.g. United States">
              <span class="error-msg" id="err-ar-country"></span>
            </div>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>City</label>
              <input type="text" id="ar-city" placeholder="e.g. Austin">
            </div>
            <div class="form-group">
              <label>State</label>
              <input type="text" id="ar-state" placeholder="e.g. Texas">
            </div>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>Product Name <span class="required">*</span></label>
              <input type="text" id="ar-productName" value="${escapeHtml(currentCampaign.productName || "")}" placeholder="e.g. Organic Ceylon Cinnamon">
              <span class="error-msg" id="err-ar-productName"></span>
            </div>
            <div class="form-group">
              <label>Source / Website Link</label>
              <input type="url" id="ar-sourceUrl" placeholder="e.g. https://company.com/about">
            </div>
          </div>
          <div class="form-actions">
            <button type="button" class="btn btn-secondary" id="arCancelBtn">Cancel</button>
            <button type="submit" class="btn btn-primary" id="arSubmitBtn">&#10010; Add Recipient</button>
          </div>
        </form>
      </div>
    `;

    document.getElementById("arCancelBtn").addEventListener("click", () => toggleAddRecipientForm());
    document.getElementById("addRecipientForm").addEventListener("submit", submitNewRecipient);
  }

  function setAddRecipientFieldError(field, msg) {
    const input = document.getElementById("ar-" + field);
    const err = document.getElementById("err-ar-" + field);
    if (input) input.classList.toggle("error", !!msg);
    if (err) {
      err.textContent = msg || "";
      err.style.display = msg ? "block" : "none";
    }
  }

  function clearAddRecipientErrors() {
    ["buyerName", "companyName", "email", "country", "productName"].forEach((f) => setAddRecipientFieldError(f, ""));
  }

  async function submitNewRecipient(e) {
    e.preventDefault();
    clearAddRecipientErrors();

    const data = {
      buyerName: document.getElementById("ar-buyerName").value.trim(),
      companyName: document.getElementById("ar-companyName").value.trim(),
      email: document.getElementById("ar-email").value.trim().toLowerCase(),
      country: document.getElementById("ar-country").value.trim(),
      city: document.getElementById("ar-city").value.trim(),
      state: document.getElementById("ar-state").value.trim(),
      productName: document.getElementById("ar-productName").value.trim(),
      sourceUrl: document.getElementById("ar-sourceUrl").value.trim(),
    };

    let valid = true;
    for (const [field, label] of Object.entries({
      buyerName: "Buyer name is required",
      companyName: "Company name is required",
      email: "Email is required",
      country: "Country is required",
      productName: "Product name is required",
    })) {
      if (!data[field]) {
        setAddRecipientFieldError(field, label);
        valid = false;
      }
    }
    if (data.email && !EMAIL_RE.test(data.email)) {
      setAddRecipientFieldError("email", "Enter a valid email address");
      valid = false;
    }
    if (data.email && EMAIL_RE.test(data.email) && activeRecipients().some((r) => (r.email || "").trim().toLowerCase() === data.email)) {
      setAddRecipientFieldError("email", "A recipient with this email already exists in the list");
      valid = false;
    }
    if (!valid) return;

    const btn = document.getElementById("arSubmitBtn");
    btn.disabled = true;
    btn.textContent = "Adding...";

    try {
      await API.createBuyer(data);
      currentRecipients = await API.getCampaignRecipients(currentCampaign.id);
      currentRecipients.forEach((r) => { r.savedEmail = r.email || ""; });
      toggleAddRecipientForm();
      renderRecipients();
      showToast(`Recipient ${data.email} added to send list`, "success");
    } catch (err) {
      showToast("Failed to add recipient: " + err.message, "error");
      if (/email/i.test(err.message)) setAddRecipientFieldError("email", err.message);
    } finally {
      btn.disabled = false;
      btn.innerHTML = "&#10010; Add Recipient";
    }
  }

  async function sendCampaignFromFrontend() {
    const list = activeRecipients();
    if (list.length === 0) {
      showToast("No recipients to send to. Add at least one recipient.", "error");
      return;
    }

    list.forEach((r) => {
      const input = document.getElementById("recipient-email-" + r.buyerId);
      if (input) r.email = input.value.trim().toLowerCase();
    });

    let valid = true;
    const seen = new Set();
    for (const r of list) {
      const email = (r.email || "").trim().toLowerCase();
      let msg = "";
      if (!email) msg = "Email is required";
      else if (!EMAIL_RE.test(email)) msg = "Invalid email format";
      else if (seen.has(email)) msg = "Duplicate email";
      seen.add(email);
      if (msg) {
        valid = false;
        setRecipientError(r.buyerId, msg);
      }
    }
    if (!valid) {
      showToast("Please fix invalid emails before sending", "error");
      return;
    }

    for (const r of list) {
      if ((r.email || "") !== (r.savedEmail || "")) {
        try {
          await API.updateRecipientEmail(currentCampaign.id, r.buyerId, r.email);
          r.savedEmail = r.email;
          setRecipientError(r.buyerId, "");
        } catch (err) {
          valid = false;
          setRecipientError(r.buyerId, err.message);
        }
      }
    }
    if (!valid) {
      showToast("Please fix invalid emails before sending", "error");
      return;
    }

    if (!confirm(`Send "${currentCampaign.campaignName}" to ${list.length} recipient(s)? Emails will be delivered via ${currentCampaign.senderEmail}.`)) return;

    const btn = document.getElementById("detailSendBtn");
    const resultWrap = document.getElementById("sendResultWrap");
    btn.disabled = true;
    btn.textContent = "Sending...";
    resultWrap.innerHTML = `
      <div class="panel">
        <div class="panel-body">
          <div class="state-message">
            <div class="spinner"></div>
            <p id="sendProgressText">Starting campaign send... 0/${list.length} processed.</p>
          </div>
        </div>
      </div>
    `;
    resultWrap.scrollIntoView({ behavior: "smooth", block: "center" });

    try {
      const payload = list.map((r) => ({ buyerId: r.buyerId, email: r.email.trim().toLowerCase() }));
      await API.sendCampaign(currentCampaign.id, payload);
      startSendPolling();
    } catch (err) {
      showSendError(err.message);
    }
  }

  function stopSendPolling() {
    if (sendPollTimer) {
      clearInterval(sendPollTimer);
      sendPollTimer = null;
    }
  }

  function showSendError(message) {
    stopSendPolling();
    const btn = document.getElementById("detailSendBtn");
    const resultWrap = document.getElementById("sendResultWrap");
    if (btn) {
      btn.disabled = false;
      btn.innerHTML = "&#10148; Send Campaign";
    }
    if (resultWrap) {
      resultWrap.innerHTML = `
        <div class="panel">
          <div class="panel-body">
            <div class="state-message error-state">
              <div class="state-icon">&#9888;</div>
              <p>Send failed: ${escapeHtml(message)}</p>
            </div>
          </div>
        </div>
      `;
    }
    showToast("Error sending campaign: " + message, "error");
  }

  function startSendPolling() {
    stopSendPolling();
    const tick = async () => {
      try {
        const status = await API.getSendStatus(currentCampaign.id);
        if (status.running) {
          const p = document.getElementById("sendProgressText");
          if (p) {
            p.textContent = `Sending campaign... ${status.processed}/${status.total} processed \u2014 Sent: ${status.sent}, Failed: ${status.failed}, Skipped: ${status.skipped}.`;
          }
          return;
        }
        stopSendPolling();
        finishSend(status);
      } catch (err) {
        stopSendPolling();
        showSendError(err.message);
      }
    };
    sendPollTimer = setInterval(tick, 2000);
    tick();
  }

  async function finishSend(status) {
    const btn = document.getElementById("detailSendBtn");
    if (btn) {
      btn.disabled = false;
      btn.innerHTML = "&#10148; Send Campaign";
    }

    if (status.error) {
      showSendError(status.error);
      return;
    }
    if (status.result) renderSendResult(status.result);

    try {
      currentRecipients = await API.getCampaignRecipients(currentCampaign.id);
      removedRecipientIds = new Set();
      renderRecipients();
    } catch (_) {}
  }

  async function resumeSendStatusCheck() {
    try {
      const status = await API.getSendStatus(currentCampaign.id);
      if (!status || !status.running) return;

      const btn = document.getElementById("detailSendBtn");
      if (btn) {
        btn.disabled = true;
        btn.textContent = "Sending...";
      }
      const resultWrap = document.getElementById("sendResultWrap");
      if (resultWrap) {
        resultWrap.innerHTML = `
          <div class="panel">
            <div class="panel-body">
              <div class="state-message">
                <div class="spinner"></div>
                <p id="sendProgressText">Sending campaign... ${status.processed}/${status.total} processed.</p>
              </div>
            </div>
          </div>
        `;
        resultWrap.scrollIntoView({ behavior: "smooth", block: "center" });
      }
      startSendPolling();
    } catch (_) {}
  }

  function renderSendResult(result) {
    const wrap = document.getElementById("sendResultWrap");
    if (!wrap) return;

    const total = result.totalEligible + result.totalSkipped;
    const rows = (result.results || []).map((r) => `
      <tr>
        <td>${escapeHtml(r.buyerName || "-")}</td>
        <td>${escapeHtml(r.email || "-")}</td>
        <td>${badge(r.status)}</td>
        <td>${escapeHtml(r.message || "-")}</td>
      </tr>
    `).join("");

    wrap.innerHTML = `
      <div class="panel">
        <div class="panel-header">
          <h2>Send Result - ${escapeHtml(result.campaignName || "")}</h2>
          <span class="recipients-hint">Completed ${formatDateTime(result.completedAt)}</span>
        </div>
        <div class="send-summary">
          <div class="stat-card"><span class="stat-label">Total Recipients</span><span class="stat-value">${total}</span></div>
          <div class="stat-card"><span class="stat-label">Sent</span><span class="stat-value stat-sent">${result.totalSent}</span></div>
          <div class="stat-card"><span class="stat-label">Failed</span><span class="stat-value stat-failed">${result.totalFailed}</span></div>
          <div class="stat-card"><span class="stat-label">Skipped</span><span class="stat-value stat-skipped">${result.totalSkipped}</span></div>
        </div>
        ${(result.results || []).length > 0 ? `
        <div class="table-wrap">
          <table>
            <thead>
              <tr><th>Buyer</th><th>Email</th><th>Status</th><th>Detail</th></tr>
            </thead>
            <tbody>${rows}</tbody>
          </table>
        </div>` : ""}
      </div>
    `;
    wrap.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function showCreateForm(prefill) {
    editingId = prefill ? prefill.id : null;
    const v = (field) => (prefill && prefill[field] ? prefill[field] : "");

    document.getElementById("campaignTabContent").innerHTML = `
      <div class="panel">
        <div class="panel-header">
          <h2>${editingId ? "Edit Campaign" : "Create Campaign"}</h2>
        </div>
        <div class="panel-body">
          <form id="campaignForm" novalidate>
            <div class="form-section">
              <h3>Email Content</h3>
              <div class="form-row">
                <div class="form-group">
                  <label>Campaign Name <span class="required">*</span></label>
                  <input type="text" id="f-campaignName" value="${escapeHtml(v("campaignName"))}" placeholder="e.g. Summer Sale 2026">
                  <span class="error-msg" id="err-campaignName"></span>
                </div>
                <div class="form-group">
                  <label>Email Subject <span class="required">*</span></label>
                  <input type="text" id="f-emailSubject" value="${escapeHtml(v("emailSubject"))}" placeholder="e.g. Exclusive Export Offer">
                  <span class="error-msg" id="err-emailSubject"></span>
                </div>
              </div>
              <div class="form-row single">
                <div class="form-group">
                  <label>Email Body <span class="required">*</span></label>
                  <div class="html-editor" id="htmlEditor">
                    <div class="editor-tabs">
                      <button type="button" class="editor-tab active" data-emode="design">&#127912; Design</button>
                      <button type="button" class="editor-tab" data-emode="html">&lt;/&gt; HTML</button>
                      <button type="button" class="editor-tab" data-emode="preview">&#128065; Preview</button>
                    </div>
                    <div class="editor-toolbar" id="editorToolbar">
                      <button type="button" class="tb-btn" data-cmd="bold" title="Bold"><b>B</b></button>
                      <button type="button" class="tb-btn" data-cmd="italic" title="Italic"><i>I</i></button>
                      <button type="button" class="tb-btn" data-cmd="underline" title="Underline"><u>U</u></button>
                      <span class="tb-sep"></span>
                      <button type="button" class="tb-btn tb-block" data-block="h1" title="Heading 1">H1</button>
                      <button type="button" class="tb-btn tb-block" data-block="h2" title="Heading 2">H2</button>
                      <button type="button" class="tb-btn tb-block" data-block="p" title="Paragraph">P</button>
                      <span class="tb-sep"></span>
                      <label class="tb-color" title="Text color"><b>A</b><input type="color" id="tbForeColor" value="#222222"></label>
                      <label class="tb-color" title="Background highlight">&#9639;<input type="color" id="tbBackColor" value="#fff3cd"></label>
                      <span class="tb-sep"></span>
                      <button type="button" class="tb-btn" data-cmd="justifyLeft" title="Align left">&#8676;</button>
                      <button type="button" class="tb-btn" data-cmd="justifyCenter" title="Align center">&#8596;</button>
                      <button type="button" class="tb-btn" data-cmd="justifyRight" title="Align right">&#8677;</button>
                      <span class="tb-sep"></span>
                      <button type="button" class="tb-btn" data-insert="button" title="Insert button">Button</button>
                      <button type="button" class="tb-btn" data-insert="box" title="Insert colored box">Box</button>
                      <button type="button" class="tb-btn" data-insert="divider" title="Insert divider">&mdash;</button>
                      <button type="button" class="tb-btn" data-insert="spacer" title="Insert spacer">Spacer</button>
                      <span class="tb-sep"></span>
                      <button type="button" class="tb-btn tb-template" id="tbTemplateBtn" title="Load professional email template">Template</button>
                    </div>
                    <div class="editor-pane" id="designPane">
                      <div id="designCanvas" contenteditable="true" spellcheck="false"></div>
                    </div>
                    <textarea class="editor-pane source-pane" id="f-emailBody" rows="16" style="display:none" placeholder="Paste or edit email HTML here..."></textarea>
                    <iframe class="editor-pane" id="previewFrame" style="display:none;border:none;width:100%;min-height:480px;background:#ffffff;" sandbox="" title="Email preview"></iframe>
                  </div>
                  <span class="error-msg" id="err-emailBody"></span>
                  <span class="recipients-hint">Design renders as the real email (HTML + inline CSS, Gmail-compatible). Use {{buyerName}}, {{companyName}}, {{productName}}, {{country}} for personalization.</span>
                </div>
              </div>
              <div class="form-row">
                <div class="form-group">
                  <label>Target Audience</label>
                  <input type="text" id="f-targetAudience" value="${escapeHtml(v("targetAudience"))}" placeholder="e.g. Importers in Europe">
                </div>
                <div class="form-group">
                  <label>Sending Limit</label>
                  <input type="number" id="f-sendingLimit" min="1" value="${v("sendingLimit")}" placeholder="e.g. 100">
                  <span class="error-msg" id="err-sendingLimit"></span>
                </div>
              </div>
              <div class="form-row single">
                <div class="form-group">
                  <label>Delay Between Emails (seconds)</label>
                  <input type="number" id="f-delayBetweenEmails" min="0" value="${v("delayBetweenEmails")}" placeholder="e.g. 30">
                  <span class="error-msg" id="err-delayBetweenEmails"></span>
                </div>
              </div>
            </div>

            <div class="form-section">
              <h3>Product Information</h3>
              <div class="form-row">
                <div class="form-group">
                  <label>Product Name</label>
                  <input type="text" id="f-productName" value="${escapeHtml(v("productName"))}" placeholder="e.g. Organic Ceylon Cinnamon">
                </div>
                <div class="form-group">
                  <label>Product Category</label>
                  <input type="text" id="f-productCategory" value="${escapeHtml(v("productCategory"))}" placeholder="e.g. Spices &amp; Herbs">
                </div>
              </div>
              <div class="form-row">
                <div class="form-group">
                  <label>Export Availability</label>
                  <select id="f-exportAvailability">
                    <option value="">-- Select --</option>
                    <option value="Available" ${v("exportAvailability") === "Available" ? "selected" : ""}>Available</option>
                    <option value="Not Available" ${v("exportAvailability") === "Not Available" ? "selected" : ""}>Not Available</option>
                    <option value="On Request" ${v("exportAvailability") === "On Request" ? "selected" : ""}>On Request</option>
                  </select>
                </div>
                <div class="form-group">
                  <label>Shipping Availability</label>
                  <select id="f-shippingAvailability">
                    <option value="">-- Select --</option>
                    <option value="Worldwide" ${v("shippingAvailability") === "Worldwide" ? "selected" : ""}>Worldwide</option>
                    <option value="Limited Regions" ${v("shippingAvailability") === "Limited Regions" ? "selected" : ""}>Limited Regions</option>
                    <option value="Not Available" ${v("shippingAvailability") === "Not Available" ? "selected" : ""}>Not Available</option>
                  </select>
                </div>
              </div>
              <div class="form-row">
                <div class="form-group">
                  <label>Minimum Order Quantity (MOQ)</label>
                  <input type="text" id="f-minimumOrderQuantity" value="${escapeHtml(v("minimumOrderQuantity"))}" placeholder="e.g. 500 kg">
                </div>
                <div class="form-group">
                  <label>Customization Options</label>
                  <input type="text" id="f-customizationOptions" value="${escapeHtml(v("customizationOptions"))}" placeholder="e.g. Private labeling available">
                </div>
              </div>
            </div>

            <div class="form-section">
              <h3>Sender Details</h3>
              <div class="form-row">
                <div class="form-group">
                  <label>Sender Name <span class="required">*</span></label>
                  <input type="text" id="f-senderName" value="${escapeHtml(v("senderName"))}" placeholder="e.g. John Smith">
                  <span class="error-msg" id="err-senderName"></span>
                </div>
                <div class="form-group">
                  <label>Company Name <span class="required">*</span></label>
                  <input type="text" id="f-senderCompany" value="${escapeHtml(v("senderCompany"))}" placeholder="e.g. Ceylon Exports Ltd.">
                  <span class="error-msg" id="err-senderCompany"></span>
                </div>
              </div>
              <div class="form-row">
                <div class="form-group">
                  <label>Sender Email <span class="required">*</span></label>
                  <input type="email" id="f-senderEmail" value="${escapeHtml(v("senderEmail"))}" placeholder="e.g. john@company.com">
                  <span class="error-msg" id="err-senderEmail"></span>
                </div>
                <div class="form-group">
                  <label>Phone Number</label>
                  <input type="tel" id="f-senderPhone" value="${escapeHtml(v("senderPhone"))}" placeholder="e.g. +94 77 123 4567">
                </div>
              </div>
            </div>

            <div class="form-section">
              <h3>Attachment</h3>
              <div class="form-row single">
                <div class="form-group">
                  <label>Company Presentation / Catalogue</label>
                  <div class="file-upload" id="fileUploadArea">
                    <input type="file" id="f-file" accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.jpg,.jpeg,.png">
                    <div class="file-upload-icon">&#128206;</div>
                    <div class="file-upload-text">Click to upload or drag and drop<br>PDF, DOC, PPT, XLS, JPG, PNG (max 10 MB)</div>
                    <div class="file-upload-name" id="f-fileName">${prefill && prefill.attachmentName ? escapeHtml(prefill.attachmentName) : ""}</div>
                  </div>
                </div>
              </div>
            </div>

            <div class="form-actions">
              <button type="button" class="btn btn-secondary" id="formCancelBtn">Cancel</button>
              <button type="submit" class="btn btn-primary" id="formSubmitBtn">${editingId ? "Update Campaign" : "Save Campaign"}</button>
            </div>
          </form>
        </div>
      </div>
    `;

    document.getElementById("formCancelBtn").addEventListener("click", () => {
      editingId = null;
      showList();
    });

    document.getElementById("f-file").addEventListener("change", function () {
      const name = this.files.length > 0 ? this.files[0].name : "";
      const el = document.getElementById("f-fileName");
      el.textContent = name;
      el.style.display = name ? "block" : "none";
    });

    initBodyEditor(prefill && prefill.emailBody ? prefill.emailBody : "");

    document.getElementById("campaignForm").addEventListener("submit", onSubmit);
  }

  let editorMode = "design";

  const EMAIL_SKELETON = (innerContent) => `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#f4f5f7;">
<tr><td align="center" style="padding:24px 12px;">
<table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0" style="width:600px;max-width:600px;background-color:#ffffff;border-radius:8px;border:1px solid #e5e7eb;font-family:Arial,Helvetica,sans-serif;">
<tr><td style="padding:32px 40px;color:#333333;font-size:14px;line-height:1.7;" data-email-content="true">${innerContent}</td></tr>
</table>
</td></tr>
</table>`;

  const EMAIL_TEMPLATE = EMAIL_SKELETON(`
<h1 style="margin:0 0 8px 0;color:#111111;font-size:22px;line-height:1.3;">{{productName}}</h1>
<p style="margin:0 0 20px 0;color:#2563eb;font-size:13px;font-weight:bold;letter-spacing:1px;">EXCLUSIVE OFFER FOR {{buyerName}}</p>
<p style="margin:0 0 14px 0;">Hello {{buyerName}},</p>
<p style="margin:0 0 18px 0;">My name is {{senderName}} from <strong>{{companyName}}</strong>. We are reaching out because your company specializes in products like ours, and we believe there is a strong opportunity to work together.</p>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr><td style="background-color:#eff6ff;border-left:4px solid #2563eb;padding:16px 20px;border-radius:4px;">
<p style="margin:0;"><strong>Product:</strong> {{productName}}</p>
<p style="margin:6px 0 0 0;"><strong>Best For:</strong> {{country}} markets</p>
</td></tr></table>
<div style="height:20px;line-height:20px;">&nbsp;</div>
<p style="margin:0 0 18px 0;">Would you be open to a short conversation this week? Reply to this email or click below and we will send our full catalogue and best pricing.</p>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr><td align="center" style="background-color:#2563eb;border-radius:6px;mso-padding-alt:12px 28px;">
<a href="#" target="_blank" style="display:inline-block;padding:12px 28px;color:#ffffff;text-decoration:none;font-weight:bold;font-size:15px;font-family:Arial,Helvetica,sans-serif;">Get Catalogue &amp; Pricing</a>
</td></tr></table>
<div style="height:24px;line-height:24px;">&nbsp;</div>
<p style="margin:0;">Best regards,<br><strong>{{senderName}}</strong><br>{{companyName}}</p>`);

  const INSERT_SNIPPETS = {
    button: `<table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr><td align="center" style="background-color:#2563eb;border-radius:6px;mso-padding-alt:11px 26px;">
<a href="#" target="_blank" style="display:inline-block;padding:11px 26px;color:#ffffff;text-decoration:none;font-weight:bold;font-size:14px;font-family:Arial,Helvetica,sans-serif;">Click Here</a>
</td></tr></table><div style="height:12px;line-height:12px;">&nbsp;</div>`,
    box: `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr><td style="background-color:#fff3cd;border-left:4px solid #d97706;padding:14px 18px;border-radius:4px;color:#333333;font-size:14px;">
<p style="margin:0;">Highlighted text here</p>
</td></tr></table><div style="height:12px;line-height:12px;">&nbsp;</div>`,
    divider: `<hr style="border:none;border-top:1px solid #e5e7eb;margin:20px 0;">`,
    spacer: `<div style="height:20px;line-height:20px;">&nbsp;</div>`,
  };

  const INLINE_TAG_STYLES = {
    H1: "margin:0 0 14px 0;",
    H2: "margin:0 0 10px 0;",
    H3: "margin:0 0 8px 0;",
    P: "margin:0 0 12px 0;",
    A: "color:#2563eb;",
  };

  function initBodyEditor(initialHtml) {
    editorMode = "design";
    const canvas = document.getElementById("designCanvas");
    if (initialHtml && /<[a-z!/][^>]*>/i.test(initialHtml)) {
      canvas.innerHTML = initialHtml;
    } else if (initialHtml && initialHtml.trim()) {
      canvas.innerHTML = EMAIL_SKELETON(escapeHtml(initialHtml).replace(/\r?\n/g, "<br>"));
    } else {
      canvas.innerHTML = EMAIL_TEMPLATE;
    }

    document.querySelectorAll("#htmlEditor .editor-tab").forEach((tab) => {
      tab.addEventListener("click", () => setEditorMode(tab.dataset.emode));
    });

    document.querySelectorAll("#editorToolbar .tb-btn[data-cmd]").forEach((btn) => {
      btn.addEventListener("mousedown", (e) => e.preventDefault());
      btn.addEventListener("click", () => {
        focusCanvas();
        document.execCommand(btn.dataset.cmd, false, null);
      });
    });

    document.querySelectorAll("#editorToolbar .tb-btn[data-block]").forEach((btn) => {
      btn.addEventListener("mousedown", (e) => e.preventDefault());
      btn.addEventListener("click", () => {
        focusCanvas();
        document.execCommand("formatBlock", false, btn.dataset.block.toUpperCase());
      });
    });

    document.querySelectorAll("#editorToolbar .tb-btn[data-insert]").forEach((btn) => {
      btn.addEventListener("mousedown", (e) => e.preventDefault());
      btn.addEventListener("click", () => {
        focusCanvas();
        document.execCommand("insertHTML", false, INSERT_SNIPPETS[btn.dataset.insert] || "");
      });
    });

    document.getElementById("tbForeColor").addEventListener("input", function () {
      focusCanvas();
      document.execCommand("foreColor", false, this.value);
    });
    document.getElementById("tbBackColor").addEventListener("input", function () {
      focusCanvas();
      document.execCommand("hiliteColor", false, this.value);
    });

    document.getElementById("tbTemplateBtn").addEventListener("click", () => {
      if (!confirm("Replace current design with the professional email template?")) return;
      canvas.innerHTML = EMAIL_TEMPLATE;
    });

    canvas.addEventListener("keydown", (e) => {
      if (e.key === "Tab") {
        e.preventDefault();
        document.execCommand("insertHTML", false, "&nbsp;&nbsp;&nbsp;&nbsp;");
      }
    });
  }

  function focusCanvas() {
    const canvas = document.getElementById("designCanvas");
    if (canvas && document.activeElement !== canvas && editorMode === "design") canvas.focus();
  }

  function inlineTagStyles(root) {
    root.querySelectorAll("h1,h2,h3,p,a").forEach((el) => {
      const extra = INLINE_TAG_STYLES[el.tagName];
      if (!extra) return;
      const existing = el.getAttribute("style");
      el.setAttribute("style", existing
        ? extra.replace(/;$/, "") + ";" + existing
        : extra);
    });
    return root;
  }

  function exportDesign() {
    const canvas = document.getElementById("designCanvas");
    const clone = canvas.cloneNode(true);
    clone.removeAttribute("contenteditable");
    clone.removeAttribute("spellcheck");
    clone.querySelectorAll("[contenteditable],[spellcheck]").forEach((el) => {
      el.removeAttribute("contenteditable");
      el.removeAttribute("spellcheck");
    });
    inlineTagStyles(clone);
    return clone.innerHTML.trim() || EMAIL_TEMPLATE;
  }

  function getEditorValue() {
    const ta = document.getElementById("f-emailBody");
    if (editorMode === "html") return ta.value;
    return exportDesign();
  }

  function buildPreviewDoc(bodyHtml) {
    return `<!DOCTYPE html><html><head><meta charset="utf-8"></head><body style="margin:0;background:#ffffff;">${bodyHtml}</body></html>`;
  }

  function setEditorMode(next) {
    if (next === editorMode || !["design", "html", "preview"].includes(next)) return;
    const ta = document.getElementById("f-emailBody");

    if (editorMode === "design") {
      ta.value = exportDesign();
    } else if (editorMode === "preview") {
      document.getElementById("previewFrame").srcdoc = "";
    }

    editorMode = next;
    document.getElementById("designPane").style.display = next === "design" ? "" : "none";
    ta.style.display = next === "html" ? "" : "none";
    document.getElementById("editorToolbar").style.display = next === "design" ? "" : "none";
    document.querySelector(".editor-tab[data-emode='design']").classList.toggle("active", next === "design");
    document.querySelector(".editor-tab[data-emode='html']").classList.toggle("active", next === "html");
    document.querySelector(".editor-tab[data-emode='preview']").classList.toggle("active", next === "preview");

    if (next === "design") {
      document.getElementById("designCanvas").innerHTML = ta.value.trim() || EMAIL_TEMPLATE;
    } else if (next === "html") {
      ta.focus();
    } else if (next === "preview") {
      document.getElementById("previewFrame").srcdoc = buildPreviewDoc(ta.value);
    }
  }

  async function startEdit(id) {
    try {
      const c = await API.getCampaign(id);
      document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
      const createTab = document.querySelector('.tab[data-tab="create"]');
      if (createTab) createTab.classList.add("active");
      view = "edit";
      showCreateForm(c);
    } catch (err) {
      showToast("Failed to load campaign: " + err.message, "error");
    }
  }

  function clearErrors() {
    document.querySelectorAll(".error-msg").forEach((el) => {
      el.textContent = "";
      el.style.display = "none";
    });
    document.querySelectorAll(".form-group input, .form-group textarea").forEach((el) => el.classList.remove("error"));
  }

  function showFieldError(fieldId, msg) {
    const input = document.getElementById(fieldId);
    const err = document.getElementById("err-" + fieldId.replace("f-", ""));
    if (input) input.classList.add("error");
    if (err) {
      err.textContent = msg;
      err.style.display = "block";
    }
  }

  function validate() {
    clearErrors();
    let valid = true;
    const required = {
      "f-campaignName": ["campaignName", "Campaign name is required"],
      "f-emailSubject": ["emailSubject", "Email subject is required"],
      "f-emailBody": ["emailBody", "Email body is required"],
      "f-senderName": ["senderName", "Sender name is required"],
      "f-senderCompany": ["senderCompany", "Company name is required"],
      "f-senderEmail": ["senderEmail", "Sender email is required"],
    };
    for (const [id, [, msg]] of Object.entries(required)) {
      if (!document.getElementById(id).value.trim()) {
        showFieldError(id, msg);
        valid = false;
      }
    }
    const email = document.getElementById("f-senderEmail").value.trim();
    if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      showFieldError("f-senderEmail", "Enter a valid email address");
      valid = false;
    }
    return valid;
  }

  function buildFormData() {
    const fd = new FormData();
    const val = (id) => document.getElementById(id).value.trim() || null;
    const numVal = (id) => {
      const v = document.getElementById(id).value;
      return v ? parseInt(v) : null;
    };

    const campaign = {
      campaignName: document.getElementById("f-campaignName").value.trim(),
      emailSubject: document.getElementById("f-emailSubject").value.trim(),
      emailBody: document.getElementById("f-emailBody").value.trim(),
      targetAudience: val("f-targetAudience"),
      sendingLimit: numVal("f-sendingLimit"),
      delayBetweenEmails: numVal("f-delayBetweenEmails"),
      productName: val("f-productName"),
      productCategory: val("f-productCategory"),
      exportAvailability: val("f-exportAvailability"),
      customizationOptions: val("f-customizationOptions"),
      minimumOrderQuantity: val("f-minimumOrderQuantity"),
      shippingAvailability: val("f-shippingAvailability"),
      senderName: document.getElementById("f-senderName").value.trim(),
      senderCompany: document.getElementById("f-senderCompany").value.trim(),
      senderEmail: document.getElementById("f-senderEmail").value.trim(),
      senderPhone: val("f-senderPhone"),
    };

    fd.append("campaign", new Blob([JSON.stringify(campaign)], { type: "application/json" }));

    const fileInput = document.getElementById("f-file");
    if (fileInput.files.length > 0) {
      fd.append("file", fileInput.files[0]);
    }

    return fd;
  }

  async function onSubmit(e) {
    e.preventDefault();
    if (editorMode === "design") {
      document.getElementById("f-emailBody").value = getEditorValue();
    }
    if (!validate()) return;

    const btn = document.getElementById("formSubmitBtn");
    btn.disabled = true;
    btn.textContent = editingId ? "Updating..." : "Saving...";

    try {
      const fd = buildFormData();
      if (editingId) {
        await API.updateCampaign(editingId, fd);
        showToast("Campaign updated successfully", "success");
      } else {
        await API.createCampaign(fd);
        showToast("Campaign created successfully", "success");
      }
      editingId = null;
      allCampaigns = await API.getCampaigns();
      filtered = allCampaigns;
      view = "list";
      document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
      const listTab = document.querySelector('.tab[data-tab="list"]');
      if (listTab) listTab.classList.add("active");
      showList();
    } catch (err) {
      showToast("Error saving campaign: " + err.message, "error");
    } finally {
      btn.disabled = false;
      btn.textContent = editingId ? "Update Campaign" : "Save Campaign";
    }
  }

  async function deleteCampaign(id) {
    const c = allCampaigns.find((x) => x.id === id);
    const name = c ? c.campaignName : "this campaign";
    if (!confirm(`Are you sure you want to delete "${name}"?`)) return;

    try {
      await API.deleteCampaign(id);
      showToast("Campaign deleted successfully", "success");
      allCampaigns = allCampaigns.filter((x) => x.id !== id);
      filtered = filtered.filter((x) => x.id !== id);

      if (view === "detail") {
        view = "list";
        showList();
      } else {
        renderTable();
        const header = document.querySelector(".panel-header h2");
        if (header) header.textContent = `${filtered.length} Campaign${filtered.length !== 1 ? "s" : ""}`;
      }
    } catch (err) {
      showToast("Error deleting campaign: " + err.message, "error");
    }
  }

  return { render };
})();
