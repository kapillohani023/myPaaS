/* myPaaS dashboard interactions */
(function () {
    "use strict";

    const body = document.body;
    const projectId = body.dataset.projectId || "";
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content || "";
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || "X-CSRF-TOKEN";
    const logBody = document.getElementById("logConsole");
    const stateBadge = document.querySelector(".project-title-row .badge");
    const toastStack = document.getElementById("toastStack");
    let pollingTimer = null;
    let pendingStepUp = null;
    let stepUpChallenge = null;

    /* ------------------------------ helpers ------------------------------ */
    function headers(extra) {
        const h = Object.assign({}, extra || {});
        if (csrfToken) h[csrfHeader] = csrfToken;
        return h;
    }

    async function readResponse(response) {
        const text = await response.text();
        let data = null;
        if (text) {
            try { data = JSON.parse(text); } catch (e) { /* plain text */ }
        }
        if (!response.ok) {
            const message = data?.error?.message || data?.message || text || "The request could not be completed.";
            const error = new Error(message);
            error.data = data;
            error.status = response.status;
            throw error;
        }
        return data;
    }

    async function fetchJson(url, options) {
        const response = await fetch(url, Object.assign({ credentials: "same-origin" }, options || {}));
        return readResponse(response);
    }

    async function postJson(url, payload) {
        return fetchJson(url, {
            method: "POST",
            headers: headers({ "Content-Type": "application/json" }),
            body: JSON.stringify(payload || {})
        });
    }

    async function postForm(url, formData, grant) {
        const extra = {};
        if (grant) extra["X-Step-Up-Grant"] = grant;
        return fetchJson(url, {
            method: "POST",
            headers: headers(extra),
            body: formData
        });
    }

    function showToast(message, kind) {
        if (!toastStack) return;
        const toast = document.createElement("div");
        toast.className = "toast " + (kind || "");
        const icon = document.createElement("svg");
        icon.setAttribute("class", "icon icon-sm");
        const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
        use.setAttribute("href", kind === "error" ? "#i-alert" : kind === "success" ? "#i-check" : "#i-terminal");
        icon.appendChild(use);
        const text = document.createElement("span");
        text.textContent = message;
        toast.appendChild(icon);
        toast.appendChild(text);
        toastStack.appendChild(toast);
        setTimeout(() => {
            toast.style.opacity = "0";
            toast.style.transform = "translateY(4px)";
            toast.style.transition = "opacity .2s ease, transform .2s ease";
            setTimeout(() => toast.remove(), 220);
        }, 4200);
    }

    function escapeHtml(value) {
        const div = document.createElement("div");
        div.textContent = value == null ? "" : String(value);
        return div.innerHTML;
    }

    /* ------------------------------ tabs ------------------------------ */
    window.activateTab = function (name) {
        document.querySelectorAll(".tab").forEach(tab => {
            tab.classList.toggle("active", tab.dataset.tab === name);
        });
        document.querySelectorAll(".tab-panel").forEach(panel => {
            panel.classList.toggle("active", panel.id === "tab-" + name);
        });
        sessionStorage.setItem("mypaas.tab", name);
        if (history.replaceState) {
            history.replaceState(null, "", "#" + name);
        }
    };

    document.querySelectorAll(".tab").forEach(tab => {
        tab.addEventListener("click", () => activateTab(tab.dataset.tab));
    });

    const storedTab = sessionStorage.getItem("mypaas.tab")
        || (location.hash ? location.hash.substring(1) : null);
    if (storedTab && document.getElementById("tab-" + storedTab)) {
        activateTab(storedTab);
    }

    /* ------------------------------ avatar menu ------------------------------ */
    window.toggleAvatarMenu = function (event) {
        event.stopPropagation();
        const menu = document.getElementById("avatarMenu");
        if (menu) menu.classList.toggle("open");
    };
    document.addEventListener("click", () => {
        document.getElementById("avatarMenu")?.classList.remove("open");
    });

    /* ------------------------------ modals ------------------------------ */
    window.openModal = function (id) {
        const modal = document.getElementById(id);
        if (!modal) return;
        modal.hidden = false;
        document.body.style.overflow = "hidden";
        document.getElementById("avatarMenu")?.classList.remove("open");
        const focusable = modal.querySelector("input:not([type=hidden]), select, textarea")
            || modal.querySelector("button");
        if (focusable) setTimeout(() => focusable.focus(), 50);
    };

    window.closeModal = function (id) {
        const modal = document.getElementById(id);
        if (!modal) return;
        modal.hidden = true;
        if (!document.querySelector(".modal-backdrop:not([hidden])")) {
            document.body.style.overflow = "";
        }
    };

    window.backdropClose = function (event, id) {
        if (event.target && event.target.dataset && event.target.dataset.close === "true") {
            closeModal(id);
        }
    };

    document.querySelectorAll(".modal-backdrop").forEach(backdrop => {
        backdrop.addEventListener("click", event => {
            if (event.target === backdrop) closeModal(backdrop.id);
        });
    });

    document.addEventListener("keydown", event => {
        if (event.key === "Escape") {
            document.querySelectorAll(".modal-backdrop:not([hidden])").forEach(m => closeModal(m.id));
            closeStepUp();
        }
    });

    document.querySelectorAll(".modal-tab").forEach(tab => {
        tab.addEventListener("click", () => {
            document.querySelectorAll(".modal-tab").forEach(t => t.classList.remove("active"));
            document.querySelectorAll(".modal-pane").forEach(p => p.classList.remove("active"));
            tab.classList.add("active");
            document.getElementById(tab.dataset.pane)?.classList.add("active");
        });
    });

    const reopen = sessionStorage.getItem("mypaas.openModal");
    if (reopen) {
        sessionStorage.removeItem("mypaas.openModal");
        openModal(reopen);
    }

    /* ------------------------------ copy ------------------------------ */
    document.querySelectorAll("[data-copy]").forEach(button => {
        button.addEventListener("click", async () => {
            try {
                await navigator.clipboard.writeText(button.dataset.copy);
                showToast("Copied to clipboard", "success");
            } catch (e) {
                showToast("Could not copy", "error");
            }
        });
    });

    /* ------------------------------ toggles ------------------------------ */
    document.querySelectorAll("[data-toggle]").forEach(input => {
        input.addEventListener("change", async () => {
            const payload = {};
            document.querySelectorAll("[data-toggle]").forEach(item => {
                payload[item.dataset.toggle] = item.checked;
            });
            try {
                await postJson(`/dashboard/projects/${projectId}/toggles`, payload);
                showToast("Project settings saved. Deploy to apply runtime changes.", "success");
            } catch (error) {
                input.checked = !input.checked;
                showToast(error.message, "error");
            }
        });
    });

    /* ------------------------------ deployment ------------------------------ */
    function setBadge(state) {
        if (!stateBadge) return;
        const map = {
            SUCCEEDED: ["Running", "ok"],
            FAILED: ["Failed", "bad"],
            CANCELLED: ["Cancelled", "idle"],
            QUEUED: ["Queued", "busy"]
        };
        const entry = map[state] || ["Deploying", "busy"];
        stateBadge.className = "badge " + entry[1];
        stateBadge.textContent = entry[0];
        if (logBody) logBody.dataset.state = state;
    }

    function clearConsole() {
        if (!logBody) return;
        logBody.querySelectorAll(".log-line, .log-empty").forEach(line => line.remove());
    }
    window.clearConsole = clearConsole;

    function appendLog(entry) {
        if (!logBody) return;
        const empty = logBody.querySelector(".log-empty");
        if (empty) empty.remove();
        const line = document.createElement("div");
        line.className = "log-line";
        const time = document.createElement("span");
        time.className = "log-time";
        time.textContent = entry.time;
        const level = document.createElement("span");
        level.className = "log-level " + String(entry.level || "INFO").toLowerCase();
        level.textContent = entry.level;
        const message = document.createElement("span");
        message.className = "log-msg";
        message.textContent = entry.message;
        line.appendChild(time);
        line.appendChild(level);
        line.appendChild(message);
        logBody.appendChild(line);
        logBody.scrollTop = logBody.scrollHeight;
        logBody.dataset.last = entry.sequence;
    }

    async function refreshLogs(deploymentId) {
        if (!logBody || !deploymentId) return;
        const from = logBody.dataset.last || "0";
        try {
            const entries = await fetchJson(`/dashboard/projects/${projectId}/logs?deploymentId=${encodeURIComponent(deploymentId)}&from=${encodeURIComponent(from)}`);
            (entries || []).forEach(appendLog);
        } catch (error) {
            // Silent: a log stream can briefly fail while a worker starts.
        }
    }

    function stopPolling() {
        if (pollingTimer) {
            clearInterval(pollingTimer);
            pollingTimer = null;
        }
    }

    async function pollDeployment(deploymentId) {
        stopPolling();
        if (!deploymentId) return;
        const tick = async () => {
            try {
                const status = await fetchJson(`/api/v1/deployments/${deploymentId}`);
                const state = status?.deployment?.state || status?.state;
                if (state) setBadge(state);
                await refreshLogs(deploymentId);
                if (state === "SUCCEEDED") {
                    stopPolling();
                    showToast("Deployment succeeded", "success");
                } else if (state === "FAILED" || state === "CANCELLED") {
                    stopPolling();
                    showToast(state === "FAILED" ? "Deployment failed. See the log console." : "Deployment cancelled.", "error");
                }
            } catch (error) {
                // Keep polling; transient API failures should not stop the stream.
            }
        };
        await tick();
        pollingTimer = setInterval(tick, 2200);
    }

    window.deployNow = async function (ref) {
        if (!projectId) return;
        const refInput = document.getElementById("deployRef");
        const refValue = ref !== undefined ? ref : (refInput && refInput.value.trim() ? refInput.value.trim() : null);
        try {
            const result = await postJson(`/dashboard/projects/${projectId}/deploy`, { ref: refValue });
            activateTab("overview");
            if (logBody) {
                clearConsole();
                logBody.dataset.deployment = result.id;
                logBody.dataset.last = "0";
                logBody.dataset.state = "QUEUED";
            }
            setBadge(result.state || "QUEUED");
            showToast("Deployment #" + result.id + " queued", "success");
            pollDeployment(result.id);
            document.getElementById("logConsoleSection")?.scrollIntoView({ behavior: "smooth", block: "start" });
        } catch (error) {
            showToast(error.message, "error");
        }
    };

    window.deployNowFromForm = function () {
        deployNow();
    };

    window.runtimeAction = async function (action) {
        if (!projectId) return;
        if (action === "stop" && !confirm("Stop the application service? Traffic will fail until it is started again.")) {
            return;
        }
        try {
            await postJson(`/dashboard/projects/${projectId}/${action}`, {});
            showToast(action === "restart" ? "Application restart requested" : "Application stopped", "success");
            if (action === "stop") setBadge("FAILED");
        } catch (error) {
            showToast(error.message, "error");
        }
    };

    window.scrollToLogs = function () {
        activateTab("overview");
        document.getElementById("logConsoleSection")?.scrollIntoView({ behavior: "smooth", block: "start" });
        refreshLogs(logBody?.dataset.deployment);
    };

    window.rollbackDeployment = async function (deploymentId) {
        if (!confirm("Activate the release that was active before deployment #" + deploymentId + "?")) return;
        try {
            const result = await postJson(`/dashboard/projects/${projectId}/rollback`, { deploymentId: deploymentId });
            showToast("Rollback #" + result.id + " queued", "success");
            setTimeout(() => location.reload(), 600);
        } catch (error) {
            showToast(error.message, "error");
        }
    };

    window.cancelDeployment = async function (deploymentId) {
        if (!confirm("Cancel deployment #" + deploymentId + "?")) return;
        try {
            await postJson(`/dashboard/projects/${projectId}/deployments/${deploymentId}/cancel`, {});
            showToast("Cancellation requested", "success");
            setTimeout(() => location.reload(), 600);
        } catch (error) {
            showToast(error.message, "error");
        }
    };

    /* ------------------------------ files ------------------------------ */
    window.uploadFile = async function (input, context) {
        const file = input.files && input.files[0];
        if (!file) return;
        const data = new FormData();
        data.append("file", file);
        try {
            await postForm(`/dashboard/projects/${projectId}/files`, data);
            sessionStorage.setItem("mypaas.openModal", context === "modal" ? "filesModal" : "");
            sessionStorage.setItem("mypaas.tab", "files");
            showToast("File uploaded", "success");
            setTimeout(() => location.reload(), 350);
        } catch (error) {
            showToast(error.message, "error");
            input.value = "";
        }
    };

    window.deleteFile = async function (fileId) {
        if (!confirm("Delete this file? This is a sensitive action and requires verification.")) return;
        try {
            await withStepUp("FILE_DELETE", "FILE", fileId, async grant => {
                await postJsonWithGrant(`/dashboard/files/${fileId}/delete`, {}, grant);
            });
            showToast("File deleted", "success");
            sessionStorage.setItem("mypaas.openModal", "filesModal");
            setTimeout(() => location.reload(), 400);
        } catch (error) {
            showToast(error.message, "error");
        }
    };

    async function postJsonWithGrant(url, payload, grant) {
        return fetchJson(url, {
            method: "POST",
            headers: headers({ "Content-Type": "application/json", "X-Step-Up-Grant": grant }),
            body: JSON.stringify(payload || {})
        });
    }

    /* ------------------------------ configuration / environment / domains ------------------------------ */
    window.saveConfiguration = async function () {
        const form = document.getElementById("configForm");
        if (!form) return;
        const data = new FormData(form);
        try {
            const result = await postForm(`/dashboard/projects/${projectId}/configuration`, data);
            sessionStorage.setItem("mypaas.tab", "configuration");
            showToast("Configuration saved as revision " + (result?.revision ?? ""), "success");
            setTimeout(() => location.reload(), 600);
        } catch (error) {
            showToast(error.message, "error");
        }
    };

    window.addEnvironment = async function (form) {
        const data = new FormData(form);
        if (!data.get("credentialId")) data.delete("credentialId");
        try {
            await withStepUp("ENV_WRITE", "PROJECT", Number(projectId), async grant => {
                await postForm(`/dashboard/projects/${projectId}/environment`, data, grant);
            });
            sessionStorage.setItem("mypaas.tab", "environment");
            showToast("Environment entry saved", "success");
            setTimeout(() => location.reload(), 500);
        } catch (error) {
            showToast(error.message, "error");
        }
    };

    window.addDomain = async function (form) {
        const data = new FormData(form);
        try {
            await postForm(`/dashboard/projects/${projectId}/domains`, data);
            sessionStorage.setItem("mypaas.tab", "domains");
            showToast("Hostname claimed", "success");
            setTimeout(() => location.reload(), 500);
        } catch (error) {
            showToast(error.message, "error");
        }
    };

    window.deleteDomain = async function (hostname) {
        if (!confirm("Unclaim " + hostname + "?")) return;
        const data = new FormData();
        data.append("hostname", hostname);
        try {
            await postForm(`/dashboard/projects/${projectId}/domains/delete`, data);
            sessionStorage.setItem("mypaas.tab", "domains");
            showToast("Hostname unclaimed", "success");
            setTimeout(() => location.reload(), 500);
        } catch (error) {
            showToast(error.message, "error");
        }
    };

    /* ------------------------------ settings ------------------------------ */
    window.saveSettings = async function () {
        const name = document.getElementById("settingsName")?.value.trim();
        const email = document.getElementById("settingsEmail")?.value.trim();
        const original = document.getElementById("settingsEmail")?.dataset.original || "";
        const notifyDeployments = document.getElementById("notifyDeployments")?.checked ? "true" : "false";
        const notifyAlerts = document.getElementById("notifyAlerts")?.checked ? "true" : "false";
        const currentPassword = document.getElementById("currentPassword")?.value || "";
        const newPassword = document.getElementById("newPassword")?.value || "";
        const confirmPassword = document.getElementById("confirmPassword")?.value || "";
        if (!name || !email) {
            showToast("Name and email are required", "error");
            return;
        }
        if (newPassword && (currentPassword === "" || newPassword.length < 12 || newPassword !== confirmPassword)) {
            showToast("New password must be at least 12 characters and match the confirmation", "error");
            return;
        }
        const profile = new FormData();
        profile.append("displayName", name);
        profile.append("email", email);
        profile.append("notifyDeployments", notifyDeployments);
        profile.append("notifyAlerts", notifyAlerts);
        const emailChanged = email.toLowerCase() !== original.toLowerCase();
        try {
            if (emailChanged) {
                await withStepUp("SETTINGS_UPDATE", "GLOBAL", null, async grant => {
                    await postForm("/dashboard/settings/profile", profile, grant);
                });
            } else {
                await postForm("/dashboard/settings/profile", profile);
            }
            if (newPassword) {
                const passwords = new FormData();
                passwords.append("currentPassword", currentPassword);
                passwords.append("newPassword", newPassword);
                await withStepUp("SETTINGS_UPDATE", "GLOBAL", null, async grant => {
                    await postForm("/dashboard/settings/password", passwords, grant);
                });
                showToast("Settings and password saved. Refresh sessions were revoked.", "success");
            } else {
                showToast("Settings saved", "success");
            }
            const passwordPane = document.getElementById("currentPassword");
            if (passwordPane) {
                passwordPane.value = "";
                document.getElementById("newPassword").value = "";
                document.getElementById("confirmPassword").value = "";
            }
            setTimeout(() => location.reload(), 500);
        } catch (error) {
            showToast(error.message, "error");
        }
    };

    /* ------------------------------ step-up ------------------------------ */
    async function withStepUp(action, resourceType, resourceId, callback) {
        if (!csrfToken) {
            throw new Error("Your session has expired. Sign in again.");
        }
        const challenge = await postJson("/dashboard/step-up/begin", {
            action: action,
            resourceType: resourceType,
            resourceId: resourceId
        });
        return new Promise((resolve, reject) => {
            pendingStepUp = { callback, resolve, reject };
            stepUpChallenge = challenge.id;
            const hint = document.getElementById("stepUpHint");
            if (hint) {
                hint.textContent = "A 6-digit code was sent to your email. It expires in 5 minutes. The code is never written to logs or audit.";
            }
            const code = document.getElementById("stepUpCode");
            if (code) code.value = "";
            const modal = document.getElementById("stepUpModal");
            if (modal) {
                modal.hidden = false;
                document.body.style.overflow = "hidden";
                setTimeout(() => code?.focus(), 60);
            }
        });
    }

    window.completeStepUp = async function () {
        const code = document.getElementById("stepUpCode")?.value.trim();
        if (!code || !stepUpChallenge || !pendingStepUp) {
            showToast("Enter the verification code", "error");
            return;
        }
        const button = document.getElementById("stepUpVerify");
        if (button) button.disabled = true;
        try {
            const grant = await postJson("/dashboard/step-up/complete", {
                challengeId: stepUpChallenge,
                code: code
            });
            const pending = pendingStepUp;
            pendingStepUp = null;
            closeStepUp();
            try {
                await pending.callback(grant.value);
                pending.resolve();
            } catch (error) {
                pending.reject(error);
                throw error;
            }
        } catch (error) {
            showToast(error.message, "error");
        } finally {
            if (button) button.disabled = false;
        }
    };

    function closeStepUp() {
        const modal = document.getElementById("stepUpModal");
        if (modal && !modal.hidden) {
            modal.hidden = true;
            if (!document.querySelector(".modal-backdrop:not([hidden])")) {
                document.body.style.overflow = "";
            }
        }
        if (pendingStepUp) {
            const pending = pendingStepUp;
            pendingStepUp = null;
            pending.reject(new Error("Verification was cancelled"));
        }
        stepUpChallenge = null;
    }
    window.closeStepUp = closeStepUp;

    /* ------------------------------ delegated row actions ------------------------------ */
    document.addEventListener("click", event => {
        const target = event.target.closest("[data-action]");
        if (!target) return;
        const action = target.dataset.action;
        if (action === "delete-file") deleteFile(Number(target.dataset.fileId));
        if (action === "delete-domain") deleteDomain(target.dataset.hostname);
        if (action === "rollback") rollbackDeployment(Number(target.dataset.deploymentId));
        if (action === "cancel") cancelDeployment(Number(target.dataset.deploymentId));
    });

    /* ------------------------------ log polling on load ------------------------------ */
    if (logBody) {
        const state = logBody.dataset.state;
        const id = logBody.dataset.deployment;
        if (id && id !== "null" && state && !["SUCCEEDED", "FAILED", "CANCELLED"].includes(state)) {
            pollDeployment(id);
        }
    }
})();
