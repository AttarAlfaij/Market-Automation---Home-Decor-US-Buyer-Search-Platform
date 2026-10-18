const API = (() => {
  const BASE = "http://localhost:8090";

  async function request(path) {
    const res = await fetch(`${BASE}${path}`);
    const json = await res.json();

    if (!res.ok || json.success === false) {
      throw new Error(json.message || `Request failed (${res.status})`);
    }

    return json.data;
  }

  async function mutate(path, method, body) {
    const opts = { method };
    if (body instanceof FormData) {
      opts.body = body;
    } else {
      opts.headers = { "Content-Type": "application/json" };
      opts.body = JSON.stringify(body);
    }
    const res = await fetch(`${BASE}${path}`, opts);
    const json = await res.json();
    if (!res.ok || json.success === false) {
      throw new Error(json.message || `Request failed (${res.status})`);
    }
    return json;
  }

  async function checkHealth() {
    try {
      const res = await fetch(`${BASE}/api/buyers`, { method: "GET" });
      return res.ok;
    } catch {
      return false;
    }
  }

  function dateRangeQuery(startDate, endDate) {
    const params = new URLSearchParams();
    if (startDate) params.set("startDate", startDate);
    if (endDate) params.set("endDate", endDate);
    const qs = params.toString();
    return qs ? `?${qs}` : "";
  }

  return {
    getBuyers: () => request("/api/buyers"),
    getCampaigns: () => request("/api/campaigns"),
    getCampaign: (id) => request(`/api/campaigns/${id}`),
    createCampaign: (formData) => mutate("/api/campaigns", "POST", formData),
    updateCampaign: (id, formData) => mutate(`/api/campaigns/${id}`, "PUT", formData),
    deleteCampaign: (id) => mutate(`/api/campaigns/${id}`, "DELETE"),
    getCampaignRecipients: (id) => request(`/api/campaigns/${id}/recipients`),
    updateRecipientEmail: (campaignId, buyerId, email) =>
      mutate(`/api/campaigns/${campaignId}/recipients/${buyerId}`, "PUT", { email }),
    deleteRecipient: (campaignId, buyerId) =>
      mutate(`/api/campaigns/${campaignId}/recipients/${buyerId}`, "DELETE"),
    deleteRecipients: (campaignId, buyerIds) =>
      mutate(`/api/campaigns/${campaignId}/recipients/bulk`, "DELETE", { buyerIds }),
    sendCampaign: (campaignId, recipients) =>
      mutate(`/api/campaigns/${campaignId}/send`, "POST", { recipients }),
    getSendStatus: (id) => request(`/api/campaigns/${id}/send-status`),
    getEmailTrackingStats: (startDate, endDate) =>
      request("/api/email/tracking-stats" + dateRangeQuery(startDate, endDate)),
    getEmailSummary: (startDate, endDate) =>
      request("/api/email/summary" + dateRangeQuery(startDate, endDate)),
    createBuyer: (data) => mutate("/api/buyers", "POST", data),
    searchBuyersUSA: (productName, resultLimit, state, city, country) =>
      mutate("/api/buyers/search-usa", "POST", { productName, resultLimit, state, city, country }),
    getBuyersByProduct: (productName) =>
      request(`/api/buyers/product/${encodeURIComponent(productName)}`),
    discoverEmails: (buyerIds) =>
      mutate("/api/email-discovery/discover", "POST", { buyerIds }),
    // Leads & Responses
    getLeads: () => request("/api/leads"),
    addLead: (data) => mutate("/api/leads", "POST", data),
    updateLead: (id, data) => mutate(`/api/leads/${id}`, "PUT", data),
    deleteLead: (id) => mutate(`/api/leads/${id}`, "DELETE"),
    getResponses: () => request("/api/leads/responses"),
    addResponse: (data) => mutate("/api/leads/responses", "POST", data),
    getLeadsAndResponsesStats: () => request("/api/leads/stats"),
    checkForReplies: () => mutate("/api/leads/check-replies", "POST", {}),
    getImapStatus: () => request("/api/leads/imap-status"),
    checkHealth,
  };
})();
