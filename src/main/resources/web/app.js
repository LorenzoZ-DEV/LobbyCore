/* ==========================================================================================
   LobbyCore panel.

   One EventSource carries everything that changes: the server pushes a stats snapshot and the
   player list every tick, and the activity feed and the error list only when they actually
   move. Nothing here polls — a panel left open on a wall display should cost one connection,
   not a request every second.

   State lives in `state` and rendering is a pure function of it, so a frame arriving while the
   user is on another tab just updates the data and redraws when they come back.
   ========================================================================================== */
(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);

  const TAB_META = {
    overview: { icon: "i-gauge", label: "Panoramica", sub: "Stato del server in tempo reale" },
    players:  { icon: "i-users", label: "Giocatori", sub: "Chi e' online adesso" },
    activity: { icon: "i-feed", label: "Attivita", sub: "Cosa hanno fatto i giocatori" },
    errors:   { icon: "i-alert", label: "Errori", sub: "Eccezioni e warning catturati" },
    modules:  { icon: "i-blocks", label: "Moduli", sub: "Accendi e spegni a caldo" },
    config:   { icon: "i-file", label: "Config", sub: "Ogni file yml del plugin" },
    tools:    { icon: "i-tools", label: "Strumenti", sub: "Annunci, reload, console" }
  };

  const state = {
    ui: null,
    stats: null,
    players: [],
    activity: [],
    errors: [],
    history: [],
    tab: "overview",
    typeFilter: new Set(),
    playerQuery: "",
    activityQuery: "",
    currentFile: null,
    fileDirty: false
  };

  /* ------------------------------------------------------------------------ helpers */

  const pad = (n) => String(n).padStart(2, "0");

  function clock(ms) {
    const d = new Date(ms);
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  }

  function duration(ms) {
    const total = Math.floor(ms / 1000);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    if (h >= 24) return `${Math.floor(h / 24)}g ${h % 24}h`;
    return h > 0 ? `${h}h ${m}m` : `${m}m`;
  }

  function bytes(n) {
    const mb = n / 1048576;
    return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${Math.round(mb)} MB`;
  }

  /* Every string from the server is inserted as text, never as HTML: a chat line, a player
     name and a YAML parse error are all attacker-influenced, and one innerHTML here would
     make the panel the easiest XSS on the network. */
  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function icon(id) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("class", "ico");
    const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
    use.setAttribute("href", `#${id}`);
    svg.appendChild(use);
    return svg;
  }

  let toastTimer = null;
  function toast(message, tone) {
    const node = $("toast");
    node.textContent = message;
    node.dataset.tone = tone || "";
    node.dataset.show = "true";
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { node.dataset.show = "false"; }, 3600);
  }

  async function api(path, options) {
    const response = await fetch(path, Object.assign({ credentials: "same-origin" }, options));
    let body = null;
    try { body = await response.json(); } catch (ignored) { /* empty or not json */ }
    if (!response.ok) {
      throw new Error((body && body.error) || `errore ${response.status}`);
    }
    return body;
  }

  const post = (path, body) => api(path, {
    method: "POST",
    headers: { "Content-Type": "text/plain; charset=utf-8" },
    body: body === undefined ? "" : body
  });

  /* --------------------------------------------------------------------------- tabs */

  function buildTabs() {
    const nav = $("tabs");
    nav.replaceChildren();

    state.ui.tabs.forEach((name) => {
      const meta = TAB_META[name];
      if (!meta) return;

      const button = el("button", null);
      button.type = "button";
      button.dataset.tab = name;
      button.appendChild(icon(meta.icon));
      button.appendChild(el("span", null, meta.label));

      if (name === "errors") {
        const badge = el("span", "badge", "0");
        badge.hidden = true;
        badge.id = "errors-badge";
        button.appendChild(badge);
      }

      button.addEventListener("click", () => showTab(name));
      nav.appendChild(button);
    });

    // A tab the admin removed from web.yml must not stay selected.
    if (!state.ui.tabs.includes(state.tab)) state.tab = state.ui.tabs[0];
    showTab(state.tab);
  }

  function showTab(name) {
    state.tab = name;
    document.querySelectorAll("#tabs button").forEach((button) => {
      button.setAttribute("aria-current", String(button.dataset.tab === name));
    });
    document.querySelectorAll(".page").forEach((page) => {
      page.hidden = page.dataset.page !== name;
    });

    const meta = TAB_META[name];
    $("page-title").textContent = meta ? meta.label : name;
    $("page-sub").textContent = meta ? meta.sub : "";

    if (name === "config" && !$("file-list").children.length) loadFiles();
    if (name === "overview") drawChart();
  }

  /* ----------------------------------------------------------------------- overview */

  function renderStats() {
    const s = state.stats;
    if (!s) return;

    const top = $("topstats");
    top.replaceChildren();
    const add = (key, value) => {
      const wrap = el("div");
      wrap.appendChild(el("span", "k", key));
      wrap.appendChild(el("span", "v", value));
      top.appendChild(wrap);
    };
    add("Lobby", s.lobby);
    add("Online", `${s.players.online}/${s.players.max}`);
    add("TPS", s.tps.m1.toFixed(2));
    add("Uptime", duration(s.uptime));

    const cards = $("kpis");
    cards.replaceChildren();

    const tpsTone = s.tps.m1 >= 19.2 ? "ok" : s.tps.m1 >= 17 ? "warn" : "bad";
    cards.appendChild(card("TPS (1m)", s.tps.m1.toFixed(2),
      `5m ${s.tps.m5.toFixed(2)} · 15m ${s.tps.m15.toFixed(2)}`, tpsTone));

    const heapPercent = Math.round((s.heap.used / s.heap.max) * 100);
    const heapCard = card("Memoria", `${heapPercent}%`,
      `${bytes(s.heap.used)} di ${bytes(s.heap.max)}`,
      heapPercent > 90 ? "bad" : heapPercent > 75 ? "warn" : null);
    const bar = el("div", "bar");
    const fill = el("i");
    fill.style.width = `${Math.min(100, heapPercent)}%`;
    bar.appendChild(fill);
    heapCard.appendChild(bar);
    cards.appendChild(heapCard);

    cards.appendChild(card("Giocatori", String(s.players.online),
      `rete ${s.players.network} · picco ${s.players.peak}`));
    cards.appendChild(card("Moduli", `${countRunning(s.modules)}/${s.modules.length}`,
      "attivi in questo momento"));
    cards.appendChild(card("Errori", String(s.counters.errors),
      `${s.counters.severe} gravi`, s.counters.severe > 0 ? "bad"
        : s.counters.errors > 0 ? "warn" : "ok"));
    cards.appendChild(card("Thread", String(s.threads.count),
      `${s.threads.daemon} daemon`));

    const services = $("services");
    services.replaceChildren();
    const svc = (label, up) => {
      const node = el("div", "service");
      node.dataset.up = String(up);
      node.appendChild(el("i"));
      node.appendChild(el("span", null, `${label} — ${up ? "ok" : "assente"}`));
      services.appendChild(node);
    };
    svc("Database", s.services.database);
    svc("Redis", s.services.redis);
    svc("LuckPerms", s.services.luckperms);
    svc("PlaceholderAPI", s.services.placeholderapi);

    const badge = $("errors-badge");
    if (badge) {
      badge.textContent = String(s.counters.severe || s.counters.errors);
      badge.hidden = s.counters.errors === 0;
    }

    renderModules(s.modules);
  }

  function countRunning(modules) {
    return modules.filter((m) => m.running).length;
  }

  function card(key, value, meta, tone) {
    const node = el("div", "card");
    if (tone) node.dataset.tone = tone;
    node.appendChild(el("span", "k", key));
    node.appendChild(el("span", "v", value));
    if (meta) node.appendChild(el("span", "m", meta));
    return node;
  }

  /* The chart is hand-drawn on a canvas rather than pulled from a library: it is one series
     of at most 180 points, and a charting dependency would be more bytes than the whole
     panel. */
  function drawChart() {
    const canvas = $("chart");
    if (!canvas || state.tab !== "overview") return;

    const ratio = window.devicePixelRatio || 1;
    const width = canvas.clientWidth || 600;
    const height = 150;
    canvas.width = width * ratio;
    canvas.height = height * ratio;

    const ctx = canvas.getContext("2d");
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.clearRect(0, 0, width, height);

    const points = state.history;
    const accent = getComputedStyle(document.documentElement)
      .getPropertyValue("--accent").trim() || "#9863E7";
    const line = getComputedStyle(document.documentElement)
      .getPropertyValue("--line").trim() || "#2c2440";

    ctx.strokeStyle = line;
    ctx.lineWidth = 1;
    for (let i = 0; i <= 3; i++) {
      const y = 12 + (height - 28) * (i / 3);
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(width, y);
      ctx.stroke();
    }

    if (points.length < 2) {
      ctx.fillStyle = getComputedStyle(document.documentElement)
        .getPropertyValue("--muted").trim() || "#837b96";
      ctx.font = "12px ui-monospace, monospace";
      ctx.fillText("raccolta dati…", 8, height / 2);
      return;
    }

    const max = Math.max(4, ...points.map((p) => p.n));
    const x = (i) => (i / (points.length - 1)) * (width - 2) + 1;
    const y = (n) => height - 16 - (n / max) * (height - 28);

    ctx.beginPath();
    ctx.moveTo(x(0), height - 16);
    points.forEach((p, i) => ctx.lineTo(x(i), y(p.n)));
    ctx.lineTo(x(points.length - 1), height - 16);
    ctx.closePath();
    const gradient = ctx.createLinearGradient(0, 0, 0, height);
    gradient.addColorStop(0, `${accent}55`);
    gradient.addColorStop(1, `${accent}00`);
    ctx.fillStyle = gradient;
    ctx.fill();

    ctx.beginPath();
    points.forEach((p, i) => (i === 0 ? ctx.moveTo(x(i), y(p.n)) : ctx.lineTo(x(i), y(p.n))));
    ctx.strokeStyle = accent;
    ctx.lineWidth = 2;
    ctx.lineJoin = "round";
    ctx.stroke();

    const last = points[points.length - 1];
    ctx.beginPath();
    ctx.arc(x(points.length - 1), y(last.n), 3.2, 0, Math.PI * 2);
    ctx.fillStyle = accent;
    ctx.fill();

    $("chart-hint").textContent = `picco ${max} · ${points.length} campioni`;
  }

  /* ------------------------------------------------------------------------ players */

  function renderPlayers() {
    const body = $("players-table").querySelector("tbody");
    const query = state.playerQuery.toLowerCase();
    const rows = state.players.filter((p) => !query || p.name.toLowerCase().includes(query));

    body.replaceChildren();
    $("players-empty").hidden = rows.length > 0;

    rows.forEach((player) => {
      const tr = el("tr");
      tr.appendChild(el("td", "name", player.name));
      tr.appendChild(el("td", null, player.group || "—"));
      tr.appendChild(el("td", null, player.world));
      tr.appendChild(el("td", null, `${player.ping} ms`));

      const pvp = el("td");
      const tag = el("span", `tag ${player.pvp ? "bad" : "off"}`, player.pvp ? "in pvp" : "no");
      pvp.appendChild(tag);
      tr.appendChild(pvp);

      tr.appendChild(el("td", null, `${player.kills} / ${player.deaths}`));
      tr.appendChild(el("td", null, duration(player.playtime)));
      tr.appendChild(el("td", null, player.address || "—"));

      const actions = el("td", "actions");
      const message = el("button", "ghost", "Messaggio");
      message.type = "button";
      message.addEventListener("click", () => messagePlayer(player.name));
      const kick = el("button", "ghost danger", "Espelli");
      kick.type = "button";
      kick.addEventListener("click", () => kickPlayer(player.name));
      actions.appendChild(message);
      actions.appendChild(kick);
      tr.appendChild(actions);

      body.appendChild(tr);
    });
  }

  async function messagePlayer(name) {
    const text = prompt(`Messaggio per ${name}:`);
    if (!text) return;
    try {
      await post(`/api/player/message?name=${encodeURIComponent(name)}`, text);
      toast(`Inviato a ${name}.`, "ok");
    } catch (e) { toast(e.message, "bad"); }
  }

  async function kickPlayer(name) {
    const reason = prompt(`Motivo per espellere ${name}:`, "&cEspulso dallo staff.");
    if (reason === null) return;
    try {
      await post(`/api/player/kick?name=${encodeURIComponent(name)}&reason=${encodeURIComponent(reason)}`);
      toast(`${name} espulso.`, "ok");
    } catch (e) { toast(e.message, "bad"); }
  }

  /* ----------------------------------------------------------------------- activity */

  function renderActivity() {
    const types = [...new Set(state.activity.map((entry) => entry.type))].sort();
    const chips = $("activity-types");

    // Rebuilt only when the set of types changed, so clicking a filter does not fight the
    // next frame arriving and re-creating the buttons under the cursor.
    const signature = types.join(",");
    if (chips.dataset.signature !== signature) {
      chips.dataset.signature = signature;
      chips.replaceChildren();
      types.forEach((type) => {
        const button = el("button", null, type);
        button.type = "button";
        button.setAttribute("aria-pressed", String(state.typeFilter.has(type)));
        button.addEventListener("click", () => {
          if (state.typeFilter.has(type)) state.typeFilter.delete(type);
          else state.typeFilter.add(type);
          button.setAttribute("aria-pressed", String(state.typeFilter.has(type)));
          renderFeed();
        });
        chips.appendChild(button);
      });
    }
    renderFeed();
  }

  function renderFeed() {
    const list = $("feed");
    const query = state.activityQuery.toLowerCase();

    const rows = state.activity.filter((entry) => {
      if (state.typeFilter.size && !state.typeFilter.has(entry.type)) return false;
      if (!query) return true;
      return (entry.player || "").toLowerCase().includes(query)
        || (entry.detail || "").toLowerCase().includes(query);
    });

    list.replaceChildren();
    $("feed-empty").hidden = rows.length > 0;

    rows.forEach((entry) => {
      const li = el("li");
      li.dataset.type = entry.type;
      li.appendChild(el("span", "t", clock(entry.time)));
      li.appendChild(el("span", "ty", entry.type));
      li.appendChild(el("span", "who", entry.player || "—"));
      li.appendChild(el("span", "d", entry.detail || ""));
      list.appendChild(li);
    });
  }

  /* ------------------------------------------------------------------------- errors */

  function renderErrors() {
    const wrap = $("issues");
    wrap.replaceChildren();
    $("issues-empty").hidden = state.errors.length > 0;

    state.errors.forEach((issue) => {
      const details = el("details", "issue");
      details.dataset.level = issue.level;

      const summary = el("summary");
      summary.appendChild(el("span", "lvl"));
      summary.appendChild(el("span", "msg", issue.message));
      summary.appendChild(el("span", "meta",
        `x${issue.count} · ${clock(issue.last)} · ${issue.source}`));
      details.appendChild(summary);

      details.appendChild(el("pre", null,
        issue.stack || "(nessuno stack trace: il log non portava un'eccezione)"));
      wrap.appendChild(details);
    });
  }

  /* ------------------------------------------------------------------------ modules */

  function renderModules(modules) {
    const wrap = $("modules");
    const signature = modules.map((m) => `${m.id}:${m.running}:${m.enabled}`).join("|");
    if (wrap.dataset.signature === signature) return;
    wrap.dataset.signature = signature;

    wrap.replaceChildren();
    modules.forEach((module) => {
      const node = el("div", "mod");

      const label = el("label", "switch");
      const input = el("input");
      input.type = "checkbox";
      input.checked = module.running;
      // The panel cannot switch itself off: doing so would drop the connection carrying
      // the request, and the browser would sit there waiting for a reply that never comes.
      input.disabled = module.id === "web";
      input.title = input.disabled ? "Il pannello non si spegne da qui" : "";
      input.addEventListener("change", () => toggleModule(module.id, input));
      label.appendChild(input);
      label.appendChild(el("span"));
      node.appendChild(label);

      const info = el("div", "info");
      info.appendChild(el("div", "id", module.id));
      info.appendChild(el("div", "desc", module.description || module.file));
      node.appendChild(info);

      if (module.enabled !== module.running) {
        node.appendChild(el("span", "tag warn", module.enabled ? "non parte" : "temporaneo"));
      }
      wrap.appendChild(node);
    });
  }

  async function toggleModule(id, input) {
    input.disabled = true;
    try {
      const result = await api(`/api/module/toggle?id=${encodeURIComponent(id)}`, { method: "POST" });
      input.checked = result.running;
      toast(`Modulo ${id} ${result.running ? "avviato" : "fermato"}.`, "ok");
    } catch (e) {
      input.checked = !input.checked;
      toast(e.message, "bad");
    } finally {
      input.disabled = id === "web";
      // Force the next frame to redraw the list: the signature guard would otherwise
      // suppress it when the server disagrees with what we just drew.
      $("modules").dataset.signature = "";
    }
  }

  /* ------------------------------------------------------------------------- config */

  async function loadFiles() {
    try {
      const files = await api("/api/files");
      const list = $("file-list");
      list.replaceChildren();

      files.forEach((file) => {
        const li = el("li");
        const button = el("button");
        button.type = "button";
        button.dataset.path = file.path;

        const slash = file.path.lastIndexOf("/");
        if (slash >= 0) {
          button.appendChild(el("span", "dir", `${file.path.slice(0, slash + 1)}`));
          button.appendChild(el("span", null, file.path.slice(slash + 1)));
        } else {
          button.appendChild(el("span", null, file.path));
        }
        if (file.readonly) button.appendChild(el("span", "ro", "bloccato"));

        button.addEventListener("click", () => openFile(file.path));
        li.appendChild(button);
        list.appendChild(li);
      });
    } catch (e) {
      toast(e.message, "bad");
    }
  }

  async function openFile(path) {
    if (state.fileDirty && !confirm("Ci sono modifiche non salvate. Continuare?")) return;
    try {
      const file = await api(`/api/file?path=${encodeURIComponent(path)}`);
      state.currentFile = file;
      state.fileDirty = false;

      $("file-name").textContent = file.path;
      const body = $("file-body");
      body.value = file.content;
      body.disabled = file.readonly;
      $("file-save").disabled = file.readonly;
      $("file-restore").disabled = file.readonly;
      $("file-hint").textContent = file.readonly
        ? "Questo file lo scrive il plugin: si modifica in gioco, non da qui."
        : "Il salvataggio ricarica solo cio che dipende dal file. Se lo YAML non e valido, non viene scritto nulla.";

      document.querySelectorAll("#file-list button").forEach((button) => {
        button.setAttribute("aria-current", String(button.dataset.path === path));
      });
    } catch (e) {
      toast(e.message, "bad");
    }
  }

  async function saveFile() {
    if (!state.currentFile) return;
    const path = state.currentFile.path;
    try {
      await post(`/api/file?path=${encodeURIComponent(path)}`, $("file-body").value);
      state.fileDirty = false;
      toast(`${path} salvato e ricaricato.`, "ok");
      loadFiles();
    } catch (e) {
      toast(e.message, "bad");
    }
  }

  async function restoreFile() {
    if (!state.currentFile) return;
    const path = state.currentFile.path;
    if (!confirm(`Ripristinare la versione precedente di ${path}?`)) return;
    try {
      await post(`/api/file/restore?path=${encodeURIComponent(path)}`);
      toast(`${path} ripristinato.`, "ok");
      openFile(path);
    } catch (e) {
      toast(e.message, "bad");
    }
  }

  /* --------------------------------------------------------------------------- wire */

  function bind() {
    $("logout").addEventListener("click", async () => {
      await post("/api/logout").catch(() => {});
      location.reload();
    });

    $("player-search").addEventListener("input", (e) => {
      state.playerQuery = e.target.value;
      renderPlayers();
    });

    $("activity-search").addEventListener("input", (e) => {
      state.activityQuery = e.target.value;
      renderFeed();
    });

    $("activity-clear").addEventListener("click", async () => {
      if (!confirm("Svuotare il log delle attivita?")) return;
      try { await post("/api/activity/clear"); toast("Log svuotato.", "ok"); }
      catch (e) { toast(e.message, "bad"); }
    });

    $("errors-clear").addEventListener("click", async () => {
      try { await post("/api/errors/clear"); toast("Errori svuotati.", "ok"); }
      catch (e) { toast(e.message, "bad"); }
    });

    $("reload-all").addEventListener("click", async () => {
      try {
        const result = await post("/api/reload");
        toast(`Ricaricato: ${result.modules} moduli attivi.`, "ok");
      } catch (e) { toast(e.message, "bad"); }
    });

    $("file-save").addEventListener("click", saveFile);
    $("file-restore").addEventListener("click", restoreFile);

    const body = $("file-body");
    body.addEventListener("input", () => { state.fileDirty = true; });
    // Tab indents instead of leaving the textarea: this is a YAML editor, and losing focus
    // on every indent makes it unusable.
    body.addEventListener("keydown", (e) => {
      if (e.key !== "Tab") return;
      e.preventDefault();
      const start = body.selectionStart;
      const end = body.selectionEnd;
      body.value = `${body.value.slice(0, start)}  ${body.value.slice(end)}`;
      body.selectionStart = body.selectionEnd = start + 2;
      state.fileDirty = true;
    });
    document.addEventListener("keydown", (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "s" && state.tab === "config") {
        e.preventDefault();
        saveFile();
      }
    });

    $("broadcast-send").addEventListener("click", async () => {
      const text = $("broadcast-body").value.trim();
      if (!text) return;
      try {
        await post("/api/broadcast", text);
        $("broadcast-body").value = "";
        toast("Annuncio inviato.", "ok");
      } catch (e) { toast(e.message, "bad"); }
    });

    const consoleInput = $("console-input");
    const runConsole = async () => {
      const command = consoleInput.value.trim();
      if (!command) return;
      try {
        await post("/api/console", command);
        consoleInput.value = "";
        toast(`Eseguito: /${command}`, "ok");
      } catch (e) { toast(e.message, "bad"); }
    };
    $("console-send").addEventListener("click", runConsole);
    consoleInput.addEventListener("keydown", (e) => { if (e.key === "Enter") runConsole(); });

    window.addEventListener("resize", drawChart);
    window.addEventListener("beforeunload", (e) => {
      if (!state.fileDirty) return;
      e.preventDefault();
      e.returnValue = "";
    });
  }

  /* ------------------------------------------------------------------------- stream */

  function connect() {
    const source = new EventSource("/api/stream");
    const link = $("link-state");

    const setLive = (live, text) => {
      link.dataset.live = String(live);
      link.querySelector("span").textContent = text;
    };

    source.addEventListener("open", () => setLive(true, "in diretta"));

    source.addEventListener("message", (event) => {
      state.stats = JSON.parse(event.data);
      renderStats();
      setLive(true, "in diretta");
    });

    source.addEventListener("players", (event) => {
      state.players = JSON.parse(event.data);
      renderPlayers();
    });

    source.addEventListener("activity", (event) => {
      state.activity = JSON.parse(event.data);
      renderActivity();
    });

    source.addEventListener("errors", (event) => {
      state.errors = JSON.parse(event.data);
      renderErrors();
    });

    source.addEventListener("history", (event) => {
      state.history = JSON.parse(event.data);
      drawChart();
    });

    // EventSource reconnects on its own; the only job here is to say so, rather than
    // leaving a panel that silently stopped updating looking like a panel that is fine.
    source.addEventListener("error", () => setLive(false, "riconnessione…"));
  }

  /* ----------------------------------------------------------------------- branding */

  /**
   * Applies the images from web.yml. Each one is independent and each one may be blank — a
   * server that supplies only a logo gets a logo and the plain background, not a half-styled
   * panel. Paths are only ever assigned to background-image and src, never to innerHTML.
   */
  function applyBranding(branding) {
    const backdrop = $("backdrop");
    if (branding.background) {
      backdrop.style.backgroundImage = `url("${encodeURI(branding.background)}")`;
      backdrop.style.opacity = String(branding.backgroundOpacity ?? 0.1);
    }

    if (branding.mark) {
      const mark = $("brand-mark");
      mark.dataset.image = "true";
      mark.style.backgroundImage = `url("${encodeURI(branding.mark)}")`;
    }

    if (branding.logo) {
      const logo = $("server-logo");
      logo.src = encodeURI(branding.logo);
      logo.hidden = false;
      // The tab favicon follows the server logo, so a pinned tab is recognisable.
      const icon = document.querySelector("link[rel='icon']");
      if (icon) icon.href = encodeURI(branding.logo);
    }

    if (branding.texture) {
      document.documentElement.style.setProperty(
        "--rail-texture", `url("${encodeURI(branding.texture)}")`);
    }
  }

  /* --------------------------------------------------------------------------- boot */

  async function boot() {
    try {
      state.ui = await api("/api/ui");
    } catch (e) {
      // A 401 here means the session cookie went stale; the server answers "/" with the
      // login page, so a reload is the right recovery.
      location.reload();
      return;
    }

    document.documentElement.style.setProperty("--accent", state.ui.accent);
    document.title = state.ui.title;
    $("brand-title").textContent = state.ui.title;
    applyBranding(state.ui.branding || {});

    if (state.ui.theme === "light") document.body.dataset.theme = "light";
    else if (state.ui.theme === "auto"
      && window.matchMedia("(prefers-color-scheme: light)").matches) {
      document.body.dataset.theme = "light";
    }

    $("console-panel").hidden = !state.ui.console;
    $("console-off").hidden = !!state.ui.console;

    buildTabs();
    bind();

    // The history only arrives on the stream's opening frame, so it is fetched once here
    // too — otherwise a panel opened long after boot draws an empty chart until the first
    // reconnect.
    api("/api/history").then((points) => { state.history = points; drawChart(); }).catch(() => {});

    connect();
    document.body.dataset.state = "ready";
  }

  boot();
})();
