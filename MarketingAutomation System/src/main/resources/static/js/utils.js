function escapeHtml(str) {
  if (str == null) return "";
  const div = document.createElement("div");
  div.textContent = String(str);
  return div.innerHTML;
}

function badge(value) {
  if (!value) return '<span class="badge badge-default">-</span>';
  const v = String(value).toUpperCase();
  let cls = "badge-default";
  if (v === "HOT") cls = "badge-hot";
  else if (v === "WARM") cls = "badge-warm";
  else if (v === "COLD") cls = "badge-cold";
  else if (v === "SENT" || v === "CONTACTED") cls = "badge-sent";
  else if (v === "PENDING") cls = "badge-pending";
  else if (v === "HIGH") cls = "badge-high";
  else if (v === "MEDIUM") cls = "badge-medium";
  else if (v === "LOW") cls = "badge-low";
  else if (v === "FAILED") cls = "badge-failed";
  else if (v === "SKIPPED") cls = "badge-skipped";
  else if (v === "NEW") cls = "badge-new";
  else if (v === "NOT_CONTACTED") cls = "badge-new";
  else if (v === "REPLY" || v === "RESPONSE") cls = "badge-sent";
  else if (v === "INTERESTED" || v === "QUALIFIED" || v === "CONVERTED") cls = "badge-hot";
  else if (v === "LOST") cls = "badge-failed";
  return '<span class="badge ' + cls + '">' + escapeHtml(value) + "</span>";
}

function formatDate(iso) {
  if (!iso) return "-";
  try {
    const d = new Date(iso);
    return d.toLocaleDateString("en-US", { year: "numeric", month: "short", day: "numeric" });
  } catch (e) {
    return escapeHtml(iso);
  }
}

function formatDateTime(iso) {
  if (!iso) return "-";
  try {
    const d = new Date(iso);
    return d.toLocaleDateString("en-US", {
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch (e) {
    return escapeHtml(iso);
  }
}

function showToast(message, type) {
  let toast = document.getElementById("appToast");
  if (!toast) {
    toast = document.createElement("div");
    toast.id = "appToast";
    toast.className = "toast";
    document.body.appendChild(toast);
  }
  toast.textContent = message;
  toast.className = "toast " + (type || "success") + " show";
  clearTimeout(toast._timer);
  toast._timer = setTimeout(function () { toast.classList.remove("show"); }, 3500);
}
