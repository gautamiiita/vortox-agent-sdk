(function () {
  'use strict';

  var cfg = window.VortoxAgent || {};
  var ENDPOINT             = cfg.endpoint            || '/agent/chat';
  var TITLE                = cfg.title               || 'AI Assistant';
  var SUGGESTIONS          = cfg.suggestions          || [];
  var ALLOW_PAGE_SCRIPTS   = cfg.allowPageScripts     === true;
  var PAGE_API_DESCRIPTION = cfg.pageApiDescription   || '';
  // Identifies the screen the widget sits on, e.g. 'tnam:order-detail'. Vortox matches it against
  // the surfaces registered for this application; anything unrecognised falls back to the
  // application default, so a typo degrades rather than breaks.
  var SURFACE              = cfg.surface              || '';
  // Which tenant's skills and secrets the run should use. A real host does NOT set this: its relay
  // derives it from the authenticated session and discards whatever the page sent, because it
  // decides which customer's credentials get injected (TnamChatContextEnricher does exactly that).
  // It exists for embeddings with no relay in front — the sidecar's own demo page, local testing —
  // where nothing else can supply it.
  var TENANT_CODE          = cfg.tenantCode           || '';

  // ── Page actions ──────────────────────────────────────────────────────────────
  // A closed set of DOM operations the agent may request, in place of the arbitrary
  // JavaScript that allowPageScripts executes. The difference is the whole point: none of
  // these can call fetch, read cookies or evaluate a string, so a prompt-injected
  // instruction can at worst put a wrong value in a field the operator is looking at —
  // not silently POST as a logged-in user.
  //
  // The host opts in with `pageActions: true` and, ideally, an `actionScope` selector
  // bounding which part of the page is reachable. Nothing has to be declared per action.
  var PAGE_ACTIONS_ENABLED = cfg.pageActions === true;
  var ACTION_SCOPE         = cfg.actionScope || null;

  // ── Page context (read-only) ──────────────────────────────────────────────────
  // Describing the page is its own capability, deliberately separate from changing it.
  // A host that only wants "explain this screen" or "what is this form missing?" enables
  // this alone and nothing can act on the page at all. Acting implies reading, so the
  // write capabilities turn it on rather than requiring both flags to be set.
  //
  // `includeFieldValues` additionally sends what is typed into non-sensitive fields.
  // Off by default because a host screen's inputs routinely carry personal data, and
  // exporting it to an LLM should be an explicit decision per deployment, not a
  // side effect of wanting the structure. See collectPageDom.
  var SEND_PAGE_CONTEXT     = cfg.sendPageContext === true
                              || PAGE_ACTIONS_ENABLED || ALLOW_PAGE_SCRIPTS;
  var INCLUDE_FIELD_VALUES  = cfg.includeFieldValues === true;
  // Outcomes of the previous turn's actions, sent with the next message so the agent
  // learns whether what it asked for actually happened. Without this it proposes into a
  // void: a stale selector or a refusal would never reach the model.
  var lastActionReport     = null;
  var getContext           = typeof cfg.context === 'function' ? cfg.context : function () { return {}; };

  // Chat normally runs asynchronously against the sidecar directly: POST starts the run and
  // returns a runId immediately, then we poll GET {ENDPOINT}/{runId} until it's done. No single
  // HTTP call needs to stay open for the whole agent run, so REQUEST_TIMEOUT_MS can stay short —
  // MAX_WAIT_MS is the real, generous budget for how long we'll wait overall before giving up.
  //
  // ENDPOINT may instead be a same-origin proxy in front of the sidecar (e.g. a server-side
  // controller that injects auth/context and strips client-supplied LLM overrides). Such a proxy
  // may choose to absorb the polling itself and return the finished {reply, artifacts} directly
  // from the POST instead of {runId, status} — see the `data.runId` check below, which handles
  // both shapes. If a proxy does this, its own wait budget can exceed REQUEST_TIMEOUT_MS's
  // default, so the host page should raise cfg.requestTimeoutMs to match (e.g. slightly above
  // the proxy's own timeout) rather than this file being changed per deployment.
  var REQUEST_TIMEOUT_MS = cfg.requestTimeoutMs || 20000;            // 20s per HTTP call
  var POLL_INTERVAL_MS   = cfg.pollIntervalMs   || 2000;             // poll every 2s
  var MAX_WAIT_MS        = cfg.maxWaitMs        || 20 * 60 * 1000;   // 20 minutes overall

  // Poll/stream URLs default to path-append (ENDPOINT + '/' + runId), which fits a REST-ish
  // async proxy (e.g. the sidecar's own /agent/chat/{runId}). A same-origin proxy built on a
  // routing scheme that can't add path segments (e.g. a classic Spring MVC bean-name-per-URL
  // app, which can only branch on query parameters) can override these to build query-param
  // URLs instead — e.g. cfg.buildPollUrl = function (runId) { return '/agentChat.htm?runId=' +
  // encodeURIComponent(runId); }. Every existing consumer that doesn't set these is unaffected.
  var buildPollUrl = typeof cfg.buildPollUrl === 'function' ? cfg.buildPollUrl : function (runId) {
    return ENDPOINT + '/' + encodeURIComponent(runId);
  };
  var buildStreamUrl = typeof cfg.buildStreamUrl === 'function' ? cfg.buildStreamUrl : function (runId) {
    return ENDPOINT + '/' + encodeURIComponent(runId) + '/stream';
  };

  // Friendly labels for known skills in the live step trace (see showThinking/addStep below).
  // Host pages can extend/override via cfg.toolLabels; unknown tool names fall back to a
  // humanized version of their raw name rather than showing nothing.
  var DEFAULT_TOOL_LABELS = {
    file_read: 'Reading file',
    file_write: 'Writing file',
    http_request: 'Making HTTP request',
    execute_oracle_sql: 'Querying the database',
    oracle_query: 'Querying the database',
    oracle_to_studio: 'Exporting query results',
    oracle_schema: 'Reviewing database schema',
    s360_db_guide: 'Reviewing database schema',
    recall_memory: 'Recalling earlier context',
    docker_build_and_run: 'Building & running container',
    docker_compose_verify: 'Verifying the stack',
    e2e_test: 'Running browser tests',
    run_sql_query: 'Running SQL query',
    search_code: 'Searching code'
  };
  var TOOL_LABELS = Object.assign({}, DEFAULT_TOOL_LABELS, cfg.toolLabels || {});
  function humanizeTool(tool) {
    if (TOOL_LABELS[tool]) return TOOL_LABELS[tool];
    return String(tool).replace(/_/g, ' ').replace(/\b\w/g, function (c) { return c.toUpperCase(); });
  }

  var _pageCtx      = {};
  var history       = [];
  var isOpen        = false;
  var isMaximized   = false;
  var isThinking    = false;
  var steps         = [];   // live step trace for the run currently in flight (see addStep/resolveStep)
  var stepsExpanded = false; // whether the trace shows every step or only the most recent few
  var runStartedAt  = 0;

  // ── Styles ────────────────────────────────────────────────────────────────────

  var VX_FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif";

  var style = document.createElement('style');
  style.textContent = [
    /* FAB */
    '#vx-fab{position:fixed;bottom:24px;right:24px;width:48px;height:48px;background:#2563eb;',
    'border:none;border-radius:50%;cursor:pointer;display:flex;align-items:center;justify-content:center;',
    'box-shadow:0 4px 14px rgba(37,99,235,0.4);z-index:9998;transition:background 0.15s,box-shadow 0.15s;}',
    '#vx-fab:hover{background:#1d4ed8;box-shadow:0 6px 18px rgba(37,99,235,0.5);}',
    '#vx-fab svg{width:22px;height:22px;fill:none;stroke:#ffffff;stroke-width:2;stroke-linecap:round;stroke-linejoin:round;}',

    /* Panel */
    '#vx-panel{position:fixed;bottom:84px;right:24px;width:460px;max-width:calc(100vw - 32px);height:580px;',
    'background:#ffffff;border:1px solid #e2e8f0;border-radius:14px;',
    'box-shadow:0 12px 40px rgba(0,0,0,0.12),0 2px 8px rgba(0,0,0,0.06);z-index:9999;display:flex;flex-direction:column;',
    'opacity:0;transform:translateY(10px);pointer-events:none;',
    'transition:opacity 0.2s cubic-bezier(.4,0,.2,1),transform 0.2s cubic-bezier(.4,0,.2,1);}',
    '#vx-panel.vx-open{opacity:1;transform:translateY(0);pointer-events:auto;}',

    /* Maximized — docks the panel full-height against the right edge of the viewport instead of
       the small floating card, for reading/writing longer conversations side-by-side with the
       host page. Slides in from the right rather than fading up, to read as "opening a panel"
       rather than "opening a popup". */
    '#vx-panel.vx-maximized{top:0;bottom:0;right:0;height:auto;width:440px;',
    'max-width:calc(100vw - 32px);border-radius:0;box-shadow:-8px 0 30px rgba(0,0,0,0.12);',
    'transform:translateX(16px);}',
    '#vx-panel.vx-maximized.vx-open{transform:translateX(0);}',
    '#vx-panel.vx-maximized #vx-header{border-radius:0;}',
    '#vx-panel.vx-maximized #vx-form{border-radius:0;}',

    /* Header */
    '#vx-header{padding:14px 16px;background:#f8faff;border-bottom:1px solid #e2e8f0;border-radius:14px 14px 0 0;',
    'display:flex;align-items:center;justify-content:space-between;flex-shrink:0;}',
    '#vx-header-title{color:#1e293b;font-family:' + VX_FONT + ';font-size:14px;font-weight:600;}',
    '#vx-header-dot{width:8px;height:8px;border-radius:50%;background:#22c55e;margin-right:8px;',
    'box-shadow:0 0 0 2px rgba(34,197,94,0.25);animation:vx-pulse 2.4s ease-in-out infinite;}',
    '@keyframes vx-pulse{0%,100%{opacity:1}50%{opacity:.5}}',
    '#vx-header-actions{display:flex;align-items:center;gap:2px;}',
    '.vx-header-btn{background:none;border:none;color:#94a3b8;cursor:pointer;',
    'display:flex;align-items:center;justify-content:center;padding:4px;border-radius:6px;',
    'transition:color .15s,background .15s;}',
    '.vx-header-btn:hover{color:#475569;background:rgba(148,163,184,0.15);}',
    '.vx-header-btn svg{width:14px;height:14px;fill:none;stroke:currentColor;stroke-width:2;',
    'stroke-linecap:round;stroke-linejoin:round;}',
    '#vx-minimize{font-size:16px;line-height:1;padding:4px 6px;}',
    '#vx-close{font-size:20px;line-height:1;padding:2px 6px;}',

    /* Messages — the ONLY scrollable area; no overflow on individual bubbles */
    '#vx-messages{flex:1;overflow-y:auto;padding:16px 14px;display:flex;flex-direction:column;gap:12px;min-height:0;}',
    '#vx-messages::-webkit-scrollbar{width:5px;}',
    '#vx-messages::-webkit-scrollbar-track{background:transparent;}',
    '#vx-messages::-webkit-scrollbar-thumb{background:#cbd5e1;border-radius:3px;}',
    '#vx-messages::-webkit-scrollbar-thumb:hover{background:#94a3b8;}',

    /* Agent bubble — no overflow set here to avoid the overflow-x→overflow-y cascade bug */
    '.vx-msg-agent{align-self:flex-start;max-width:100%;color:#1e293b;',
    'font-family:' + VX_FONT + ';font-size:13px;line-height:1.65;',
    'padding:10px 14px;background:#f1f5f9;border-radius:4px 14px 14px 14px;word-break:break-word;}',

    /* User bubble */
    '.vx-msg-user{align-self:flex-end;max-width:82%;color:#ffffff;',
    'font-family:' + VX_FONT + ';font-size:13px;line-height:1.5;',
    'background:#2563eb;padding:10px 14px;border-radius:14px 14px 4px 14px;word-break:break-word;}',

    /* Markdown: paragraphs */
    '.vx-msg-agent .vx-p{margin:0 0 8px;}',
    '.vx-msg-agent .vx-p:last-child{margin-bottom:0;}',

    /* Markdown: headings */
    '.vx-msg-agent .vx-h{color:#1e293b;font-family:' + VX_FONT + ';font-size:13px;font-weight:700;',
    'margin:12px 0 4px;letter-spacing:.01em;}',
    '.vx-msg-agent .vx-h:first-child{margin-top:0;}',

    /* Markdown: hr */
    '.vx-msg-agent .vx-hr{border:none;border-top:1px solid #e2e8f0;margin:10px 0;}',

    /* Markdown: lists */
    '.vx-msg-agent .vx-ul,.vx-msg-agent .vx-ol{margin:4px 0 8px 18px;padding:0;}',
    '.vx-msg-agent .vx-li{margin-bottom:4px;}',
    '.vx-msg-agent .vx-ul:last-child,.vx-msg-agent .vx-ol:last-child{margin-bottom:0;}',

    /* Markdown: inline code */
    '.vx-msg-agent .vx-ic{background:#e2e8f0;border-radius:4px;',
    'padding:1px 5px;font-family:ui-monospace,\'SF Mono\',Menlo,monospace;font-size:12px;color:#0f172a;}',

    /* Markdown: code block — overflow-x goes HERE, not on the bubble */
    '.vx-msg-agent .vx-pre{background:#0f172a;border-radius:8px;',
    'padding:12px 14px;margin:8px 0;overflow-x:auto;white-space:pre;}',
    '.vx-msg-agent .vx-pre code{font-family:ui-monospace,\'SF Mono\',Menlo,monospace;',
    'font-size:12px;color:#86efac;background:none;border:none;padding:0;}',

    /* Markdown: tables */
    '.vx-msg-agent .vx-tw{overflow-x:auto;margin:8px 0;border-radius:8px;border:1px solid #e2e8f0;}',
    '.vx-msg-agent .vx-table{border-collapse:collapse;width:100%;font-size:12px;font-family:' + VX_FONT + ';}',
    '.vx-msg-agent .vx-th{background:#f8faff;color:#475569;padding:7px 12px;',
    'border-bottom:1px solid #e2e8f0;text-align:left;font-weight:600;white-space:nowrap;}',
    '.vx-msg-agent .vx-td{color:#334155;padding:6px 12px;border-bottom:1px solid #f1f5f9;}',
    '.vx-msg-agent .vx-tr:last-child .vx-td{border-bottom:none;}',
    '.vx-msg-agent .vx-tr:hover .vx-td{background:#f8faff;}',

    /* Markdown: bold / italic */
    '.vx-msg-agent strong{color:#0f172a;font-weight:700;}',
    '.vx-msg-agent em{color:#475569;font-style:italic;}',

    /* Thinking indicator — header (dots + status) plus a live-growing step trace below it */
    '.vx-thinking{align-self:flex-start;max-width:100%;display:flex;flex-direction:column;gap:6px;',
    'padding:10px 14px;background:#f1f5f9;border-radius:4px 14px 14px 14px;}',
    '.vx-thinking-header{display:flex;gap:8px;align-items:center;}',
    '.vx-dot{width:6px;height:6px;border-radius:50%;background:#94a3b8;flex-shrink:0;',
    'animation:vx-bounce 1.2s ease-in-out infinite;}',
    '.vx-dot:nth-child(2){animation-delay:.18s}.vx-dot:nth-child(3){animation-delay:.36s}',
    '@keyframes vx-bounce{0%,80%,100%{transform:translateY(0)}40%{transform:translateY(-5px)}}',
    '@keyframes vx-step-pulse{0%,100%{opacity:1}50%{opacity:.35}}',
    '.vx-thinking-label{color:#64748b;font-family:' + VX_FONT + ';font-size:12px;',
    'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:340px;}',

    /* Live step trace — a timeline of what the agent is doing, one entry per piece of work.
       Two lines per entry: what kind of step it is, and which thing it acted on. A single line
       carrying only the tool's name repeats itself as soon as the agent works iteratively, which
       reads as the same thing failing over and over rather than an investigation progressing.
       No inner scrollbar: a scroll region inside a chat bubble is cramped and hides the very
       history it is meant to show, so older entries collapse behind a count instead. */
    '.vx-steps{display:flex;flex-direction:column;}',
    '.vx-step{display:flex;gap:9px;font-family:' + VX_FONT + ';font-size:12px;color:#475569;',
    'line-height:1.45;padding:3px 0;position:relative;}',
    /* the connecting rail, drawn behind the icons */
    '.vx-step:not(:last-child)::before{content:"";position:absolute;left:6px;top:19px;bottom:-3px;',
    'width:1px;background:#e2e8f0;}',
    '.vx-step-icon{width:13px;height:13px;flex-shrink:0;margin-top:2px;text-align:center;',
    'font-size:10px;line-height:13px;border-radius:50%;background:#f1f5f9;color:#94a3b8;',
    'position:relative;z-index:1;}',
    '.vx-step-running .vx-step-icon{background:#fef3c7;color:#b45309;',
    'animation:vx-step-pulse 1.3s ease-in-out infinite;}',
    '.vx-step-done .vx-step-icon{background:#dcfce7;color:#166534;}',
    '.vx-step-failed .vx-step-icon{background:#fee2e2;color:#991b1b;}',
    '.vx-step-body{min-width:0;flex:1;}',
    '.vx-step-title{color:#334155;font-weight:500;overflow:hidden;text-overflow:ellipsis;',
    'white-space:nowrap;}',
    '.vx-step-failed .vx-step-title{color:#991b1b;}',
    /* The detail is the substance — the statement, the path, the URL — so it gets room to wrap
       to a second line rather than being clipped to nothing. */
    '.vx-step-detail{color:#94a3b8;font-size:11px;overflow:hidden;display:-webkit-box;',
    '-webkit-line-clamp:2;-webkit-box-orient:vertical;word-break:break-word;}',
    '.vx-step-more{align-self:flex-start;background:none;border:none;padding:2px 0 4px 22px;',
    'color:#64748b;font-family:' + VX_FONT + ';font-size:11px;cursor:pointer;text-align:left;}',
    '.vx-step-more:hover{color:#2563eb;text-decoration:underline;}',

    /* Collapsed step summary — rendered above the final reply once the run completes;
       click to expand/collapse the full trace (Claude Desktop's "Thought for Xs" pattern) */
    '.vx-summary{align-self:flex-start;display:inline-flex;align-items:center;gap:6px;',
    'padding:5px 10px;background:#f8faff;border:1px solid #e2e8f0;border-radius:20px;cursor:pointer;',
    'color:#64748b;font-family:' + VX_FONT + ';font-size:11px;font-weight:600;transition:background .15s;}',
    '.vx-summary:hover{background:#eff6ff;}',
    '.vx-summary-chevron{display:inline-block;transition:transform .15s;font-size:9px;}',
    '.vx-summary.vx-summary-open .vx-summary-chevron{transform:rotate(90deg);}',
    '.vx-summary-detail{display:none;align-self:flex-start;max-width:100%;background:#f8faff;',
    'border:1px solid #e2e8f0;border-radius:10px;padding:8px 12px;margin:4px 0 0;}',
    '.vx-summary-detail.vx-summary-detail-open{display:flex;flex-direction:column;gap:3px;}',
    '.vx-summary-wrap{display:flex;flex-direction:column;align-self:flex-start;max-width:100%;}',

    /* Context chips — what the agent was told about this page and user. Sits directly under the
       header, above the transcript, because it describes the whole conversation rather than any
       one message. Muted by design: it should be checkable at a glance and ignorable otherwise. */
    '#vx-context{padding:8px 14px;display:flex;flex-wrap:wrap;gap:5px;flex-shrink:0;',
    'border-bottom:1px solid #eef2f7;background:#fbfdff;}',
    '.vx-ctx-chip{background:#f1f5f9;border:1px solid #e2e8f0;color:#475569;',
    'font-family:' + VX_FONT + ';font-size:11px;line-height:1.3;padding:3px 8px;border-radius:6px;',
    'cursor:pointer;white-space:nowrap;transition:border-color .15s,color .15s;}',
    '.vx-ctx-chip:hover{border-color:#94a3b8;color:#1e293b;}',
    /* Absent context is a state worth seeing, not a chip worth hiding. */
    '.vx-ctx-off{opacity:.55;text-decoration:line-through;}',
    /* Arbitrary script execution is a standing risk, so it never reads as routine. */
    '.vx-ctx-warn{background:#fef3c7;border-color:#fcd34d;color:#92400e;}',

    /* Suggestion chips */
    '#vx-suggestions{padding:0 14px 10px;display:flex;flex-wrap:wrap;gap:6px;flex-shrink:0;}',
    '.vx-chip{background:#ffffff;border:1px solid #e2e8f0;color:#475569;',
    'font-family:' + VX_FONT + ';font-size:12px;padding:5px 11px;border-radius:20px;cursor:pointer;',
    'white-space:nowrap;transition:border-color .15s,background .15s,color .15s;}',
    '.vx-chip:hover{border-color:#2563eb;color:#2563eb;background:#eff6ff;}',

    /* Input row */
    '#vx-form{padding:10px 14px 14px;border-top:1px solid #e2e8f0;display:flex;align-items:flex-end;gap:8px;flex-shrink:0;background:#ffffff;',
    'border-radius:0 0 14px 14px;}',
    '#vx-input{flex:1;background:#f8faff !important;border:1px solid #e2e8f0 !important;border-radius:10px;',
    'color:#1e293b !important;font-family:' + VX_FONT + ' !important;font-size:13px;padding:9px 13px;outline:none;',
    'transition:border-color .15s,box-shadow .15s;box-shadow:none !important;',
    'resize:none;overflow:hidden;min-height:38px;max-height:120px;line-height:1.5;display:block;}',
    '#vx-input:focus{border-color:#2563eb !important;box-shadow:0 0 0 3px rgba(37,99,235,0.1) !important;}',
    '#vx-input::placeholder{color:#94a3b8 !important;}',
    '#vx-send{background:#2563eb;border:none;border-radius:10px;color:#ffffff;',
    'cursor:pointer;padding:9px 18px;font-family:' + VX_FONT + ';font-size:13px;font-weight:600;',
    'transition:background .15s;white-space:nowrap;}',
    '#vx-send:hover:not(:disabled){background:#1d4ed8;}',
    '#vx-send:disabled{opacity:.45;cursor:default;}',

    /* Page-script execution feedback */
    '.vx-executed-badge{display:inline-flex;align-items:center;gap:4px;margin-top:8px;padding:3px 9px;',
    'background:#f0fdf4;border:1px solid #bbf7d0;border-radius:20px;',
    'color:#15803d;font-family:' + VX_FONT + ';font-size:11px;font-weight:500;}',
    '.vx-script-error{display:block;margin-top:8px;padding:6px 10px;',
    'background:#fef2f2;border:1px solid #fecaca;border-radius:8px;',
    'color:#dc2626;font-family:ui-monospace,monospace;font-size:11px;white-space:pre-wrap;word-break:break-all;}',

    /* Downloadable artifact chip (files the agent generated, e.g. reports) */
    '.vx-artifact{display:inline-flex;align-items:center;gap:6px;margin:8px 6px 0 0;padding:6px 10px;',
    'background:#eff6ff;border:1px solid #bfdbfe;border-radius:8px;cursor:pointer;',
    'color:#1d4ed8;font-family:' + VX_FONT + ';font-size:12px;font-weight:600;',
    'transition:background .15s,border-color .15s;}',
    '.vx-artifact:hover{background:#dbeafe;border-color:#93c5fd;}',
    '.vx-artifact svg{stroke:#1d4ed8;flex-shrink:0;}',
    '.vx-artifact-size{color:#64748b;font-weight:400;margin-left:2px;}',

    /* Page actions — what the agent asked the page to do, and what actually happened */
    '.vx-actions{margin-top:9px;border:1px solid #e2e8f0;border-radius:9px;overflow:hidden;',
    'background:#fff;font-family:' + VX_FONT + ';}',
    '.vx-actions-head{padding:6px 10px;background:#f8fafc;border-bottom:1px solid #e2e8f0;',
    'font-size:10.5px;font-weight:700;letter-spacing:.06em;text-transform:uppercase;color:#475569;}',
    '.vx-actions-list{padding:3px 0;}',
    '.vx-action-row{display:flex;align-items:baseline;gap:7px;padding:5px 10px;font-size:12px;}',
    '.vx-action-name{font-family:ui-monospace,monospace;font-size:11.5px;font-weight:600;',
    'color:#be1250;flex:0 0 auto;}',
    '.vx-action-args{font-family:ui-monospace,monospace;font-size:11px;color:#64748b;',
    'overflow-wrap:anywhere;min-width:0;}',
    '.vx-action-state{margin-left:auto;flex:0 0 auto;font-size:10.5px;font-weight:700;}',
    '.vx-state-ok{color:#15803d;}',
    '.vx-state-warn{color:#b45309;}',
    '.vx-state-stop{color:#b91c1c;}',
    '.vx-state-muted{color:#94a3b8;font-weight:600;}',
    '.vx-actions-note{padding:7px 10px;font-size:11.5px;line-height:1.45;}',
    '.vx-actions-note-stop{background:#fef2f2;color:#b91c1c;}',
    '.vx-actions-foot{display:flex;align-items:center;gap:7px;padding:8px 10px;',
    'border-top:1px solid #e2e8f0;}',
    '.vx-action-btn{font-family:inherit;font-size:12px;font-weight:600;padding:5px 12px;',
    'border-radius:6px;border:1px solid #e2e8f0;background:#fff;color:#334155;cursor:pointer;}',
    '.vx-action-btn:hover{background:#f8fafc;}',
    '.vx-action-btn-primary{background:#be1250;border-color:#be1250;color:#fff;}',
    '.vx-action-btn-primary:hover{background:#a30f45;}',
    '.vx-actions-runas{margin-left:auto;font-size:10.5px;color:#94a3b8;}',
    /* Applied to the host page, so it is namespaced and deliberately unobtrusive —
       an outline rather than a background, which would fight the application\'s own styling. */
    '.vx-action-highlight{outline:2px solid #be1250 !important;outline-offset:1px !important;',
    'transition:outline-color .2s;}'
  ].join('');
  document.head.appendChild(style);

  // ── Markdown renderer ─────────────────────────────────────────────────────────

  function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  }

  function inline(s) {
    return esc(s)
      .replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>')
      .replace(/\*\*(.+?)\*\*/g,     '<strong>$1</strong>')
      .replace(/\*([^*\n]+?)\*/g,    '<em>$1</em>')
      .replace(/`([^`]+)`/g,         '<code class="vx-ic">$1</code>');
  }

  function isSep(line) {
    return /^\s*\|?[\s\-:|]+(\|[\s\-:|]+)*\|?\s*$/.test(line) &&
           line.indexOf('-') !== -1;
  }

  function renderTable(rows) {
    var data = rows.filter(function (r) { return !isSep(r); });
    if (!data.length) return '';
    var html = '<div class="vx-tw"><table class="vx-table">';
    data.forEach(function (row, ri) {
      var cells = row.split('|').map(function (c) { return c.trim(); });
      if (cells[0] === '') cells.shift();
      if (cells[cells.length - 1] === '') cells.pop();
      var tag = ri === 0 ? 'th' : 'td';
      html += '<tr class="vx-tr">' + cells.map(function (c) {
        return '<' + tag + ' class="vx-' + tag + '">' + inline(c) + '</' + tag + '>';
      }).join('') + '</tr>';
    });
    return html + '</table></div>';
  }

  function renderMarkdown(raw) {
    var out   = '';
    var lines = raw.split('\n');
    var i     = 0;

    while (i < lines.length) {
      var line = lines[i];

      // Fenced code block
      if (/^```/.test(line)) {
        var codeLines = [];
        i++;
        while (i < lines.length && !/^```/.test(lines[i])) {
          codeLines.push(esc(lines[i]));
          i++;
        }
        i++; // closing ```
        out += '<pre class="vx-pre"><code>' + codeLines.join('\n') + '</code></pre>';
        continue;
      }

      // Table
      if (/^\s*\|/.test(line)) {
        var tableRows = [];
        while (i < lines.length && /^\s*\|/.test(lines[i])) {
          tableRows.push(lines[i]);
          i++;
        }
        out += renderTable(tableRows);
        continue;
      }

      // Heading
      var hm = line.match(/^(#{1,4})\s+(.+)/);
      if (hm) {
        out += '<div class="vx-h">' + inline(hm[2]) + '</div>';
        i++;
        continue;
      }

      // Horizontal rule
      if (/^---+\s*$/.test(line)) {
        out += '<hr class="vx-hr">';
        i++;
        continue;
      }

      // Bullet list
      if (/^\s*[-*]\s+/.test(line)) {
        out += '<ul class="vx-ul">';
        while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
          out += '<li class="vx-li">' + inline(lines[i].replace(/^\s*[-*]\s+/, '')) + '</li>';
          i++;
        }
        out += '</ul>';
        continue;
      }

      // Numbered list
      if (/^\s*\d+\.\s+/.test(line)) {
        out += '<ol class="vx-ol">';
        while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
          out += '<li class="vx-li">' + inline(lines[i].replace(/^\s*\d+\.\s+/, '')) + '</li>';
          i++;
        }
        out += '</ol>';
        continue;
      }

      // Blank line
      if (line.trim() === '') {
        i++;
        continue;
      }

      // Paragraph — collect consecutive plain lines
      var paraLines = [];
      while (i < lines.length &&
             lines[i].trim() !== '' &&
             !/^```|^\s*\||^#{1,4}\s|^---+\s*$|^\s*[-*]\s+|^\s*\d+\.\s+/.test(lines[i])) {
        paraLines.push(inline(lines[i]));
        i++;
      }
      if (paraLines.length) {
        out += '<p class="vx-p">' + paraLines.join('<br>') + '</p>';
      }
    }

    return out;
  }

  // ── DOM introspection ─────────────────────────────────────────────────────────
  // Describes the live page to the agent: headings, addressable elements, and form
  // fields. Structure is what makes the agent useful on a screen — it can say what a
  // form is missing, or name the selector for an action — and it is separate from any
  // ability to change the page (see sendPageContext vs pageActions).
  //
  // Field CONTENTS are a different matter. A host screen's inputs routinely hold
  // personal data — names, emails, addresses, phone numbers — and sending them to an
  // LLM as a side effect of wanting the structure is a decision no host should make
  // implicitly. So values are opt-in (`includeFieldValues`), and even then the fields
  // most likely to carry personal data are reported as filled-or-empty rather than
  // quoted. Structure answers most questions; contents rarely add much and cost a lot.

  /**
   * Field id/label/name patterns whose contents are never sent, even with values enabled.
   * Deliberately broad: over-redacting costs the agent a detail it can ask about, while
   * under-redacting exports somebody's personal data and cannot be taken back.
   */
  var SENSITIVE_FIELD = /(pass|pwd|secret|token|mail|phone|tel|mobile|name|surname|firstname|lastname|birth|dob|address|street|city|zip|postal|iban|bic|card|cvv|ccv|account|ssn|nif|siret|vat|tax|licen|passport|identity|note|comment)/i;

  function isSensitiveField(el, labelText) {
    if (el.type === 'password') return true;
    var haystack = [el.id, el.name, el.getAttribute('placeholder'), labelText]
      .filter(Boolean).join(' ');
    return SENSITIVE_FIELD.test(haystack);
  }

  function collectPageDom() {
    var parts = [];

    // Headings give the agent a structural map of the page
    var headings = [];
    document.querySelectorAll('h1,h2,h3').forEach(function (el) {
      var text = el.textContent.trim().replace(/\s+/g, ' ');
      if (text) headings.push(el.tagName.toLowerCase() + ': ' + text);
    });
    if (headings.length) parts.push('### Page headings\n' + headings.join('\n'));

    // All elements that have an id (excluding the widget itself)
    var elements = [];
    document.querySelectorAll('[id]').forEach(function (el) {
      if (/^vx-/.test(el.id)) return;
      var tag  = el.tagName.toLowerCase();
      var type = el.getAttribute('type') ? ' type="' + el.getAttribute('type') + '"' : '';
      // Short visible text hint (collapsed whitespace, 200-char cap)
      var text = (el.getAttribute('placeholder') || el.textContent || '').trim()
                   .replace(/\s+/g, ' ').substring(0, 200);
      elements.push('#' + el.id + ' <' + tag + type + '>' + (text ? ' "' + text + '"' : ''));
    });
    if (elements.length) parts.push('### Elements with IDs\n' + elements.join('\n'));

    // Form fields: what they are and whether they are filled. Contents only when the
    // host asked for them and the field is not one that typically carries personal data.
    var fields = [];
    var redacted = 0;
    document.querySelectorAll('input,select,textarea').forEach(function (el) {
      if (!el.id || /^vx-/.test(el.id)) return;
      var label = document.querySelector('label[for="' + el.id + '"]');
      var labelText = label ? label.textContent.trim().replace(/\s+/g, ' ') : '';
      var raw = String(el.value == null ? '' : el.value);

      var state;
      if (el.type === 'checkbox' || el.type === 'radio') {
        state = 'checked=' + el.checked;
      } else if (INCLUDE_FIELD_VALUES && !isSensitiveField(el, labelText)) {
        state = 'value:"' + raw.substring(0, 40) + '"';
      } else {
        // Filled-or-empty is usually the analytically interesting part — "this required
        // field is blank" — without quoting whatever the operator typed.
        state = raw.trim() ? 'filled' : 'empty';
        if (raw.trim() && INCLUDE_FIELD_VALUES) redacted++;
      }

      fields.push('#' + el.id + ' (' + (el.type || el.tagName.toLowerCase()) + ')' +
                  (labelText ? ' label:"' + labelText + '"' : '') + ' ' + state);
    });
    if (fields.length) {
      var header = '### Form fields';
      if (!INCLUDE_FIELD_VALUES) {
        header += '\n(contents withheld by this deployment — fields are reported as filled or '
                + 'empty. Ask the user for a value rather than assuming one.)';
      } else if (redacted) {
        header += '\n(' + redacted + ' field(s) hold personal data and are reported as filled '
                + 'rather than quoted.)';
      }
      parts.push(header + '\n' + fields.join('\n'));
    }

    return parts.join('\n\n');
  }

  /** Counts what the snapshot describes, for the context indicator. */
  function pageContextStats() {
    var ids = 0;
    document.querySelectorAll('[id]').forEach(function (el) { if (!/^vx-/.test(el.id)) ids++; });
    var fields = 0;
    document.querySelectorAll('input,select,textarea').forEach(function (el) {
      if (el.id && !/^vx-/.test(el.id)) fields++;
    });
    return { elements: ids, fields: fields };
  }

  // ── Page-script execution ─────────────────────────────────────────────────────
  // Scans raw LLM reply text for ```javascript blocks and executes the first one.
  // On success appends a green badge; on error appends a red error message.
  // Uses new Function(code)() so the script has access to window/document but NOT
  // the widget's closure variables — intentional isolation.

  function extractAndRunScripts(raw, containerEl) {
    var re = /```javascript\s*([\s\S]*?)```/g;
    var match = re.exec(raw);
    if (!match) return;
    var code = match[1].trim();
    if (!code) return;
    var badge;
    try {
      new Function(code)(); // eslint-disable-line no-new-func
      badge = document.createElement('span');
      badge.className = 'vx-executed-badge';
      badge.textContent = '✓ Page updated';
    } catch (err) {
      badge = document.createElement('span');
      badge.className = 'vx-script-error';
      badge.textContent = '✗ Script error: ' + err.message;
    }
    containerEl.appendChild(badge);
  }

  // ── Page actions ──────────────────────────────────────────────────────────────

  /**
   * The closed set. `confirm: true` means the operator approves before it runs.
   *
   * Only `fill` asks. Highlighting and scrolling change nothing that survives a refresh, and
   * prompting for those would teach people to click through prompts without reading — which is
   * exactly the habit that makes the prompt on `fill` worthless when it matters.
   *
   * `click` is deliberately absent from this first cut: it is the one primitive that can trigger
   * anything on the page, up to and including a delete, and the other five are useful without it.
   */
  var PAGE_ACTION_DEFS = {
    highlight: { confirm: false, args: { target: 'selector' } },
    scrollTo:  { confirm: false, args: { target: 'selector' } },
    setClass:  { confirm: false, args: { target: 'selector', add: 'string?', remove: 'string?' } },
    setText:   { confirm: false, args: { target: 'selector', text: 'string' } },
    fill:      { confirm: true,  args: { target: 'selector', value: 'string' } }
  };

  var FILLABLE = { INPUT: 1, SELECT: 1, TEXTAREA: 1 };

  /** Elements matching `target`, restricted to actionScope if the host set one. */
  function resolveTargets(selector) {
    var root = document;
    if (ACTION_SCOPE) {
      root = document.querySelector(ACTION_SCOPE);
      if (!root) return { error: 'the page area this widget may touch (' + ACTION_SCOPE + ') is not on this page' };
    }
    var found;
    try {
      found = root.querySelectorAll(selector);
    } catch (e) {
      return { error: 'not a usable selector: ' + selector };
    }
    if (!found.length) return { error: 'nothing on the page matches ' + selector };
    return { nodes: Array.prototype.slice.call(found) };
  }

  /**
   * Checks one requested action against its definition before anything runs.
   *
   * This is the security boundary, so it rejects rather than coerces: an unknown name, a missing
   * argument, a wrong type or an argument that was never declared all fail. Silently ignoring an
   * unexpected key is how a validator stops being one.
   */
  function validateAction(action) {
    if (!action || typeof action !== 'object') return 'not a valid action';
    var def = Object.prototype.hasOwnProperty.call(PAGE_ACTION_DEFS, action.name)
      ? PAGE_ACTION_DEFS[action.name] : null;
    if (!def) return 'unknown action "' + action.name + '"';

    for (var key in action) {
      if (!Object.prototype.hasOwnProperty.call(action, key)) continue;
      if (key === 'name') continue;
      if (!Object.prototype.hasOwnProperty.call(def.args, key)) {
        return '"' + action.name + '" does not take "' + key + '"';
      }
    }

    for (var arg in def.args) {
      if (!Object.prototype.hasOwnProperty.call(def.args, arg)) continue;
      var spec = def.args[arg];
      var optional = spec.charAt(spec.length - 1) === '?';
      var present = action[arg] !== undefined && action[arg] !== null;
      if (!present) {
        if (!optional) return '"' + action.name + '" needs "' + arg + '"';
        continue;
      }
      if (typeof action[arg] !== 'string') return '"' + arg + '" must be text';
      if (spec.indexOf('selector') === 0 && !action[arg].trim()) return '"' + arg + '" is empty';
    }

    if (action.name === 'setClass' && !action.add && !action.remove) {
      return 'setClass needs "add" or "remove"';
    }
    return null;
  }

  /** Runs one already-validated action. Returns {ok, detail}. */
  function runAction(action) {
    var resolved = resolveTargets(action.target);
    if (resolved.error) return { ok: false, detail: resolved.error };
    var nodes = resolved.nodes;

    try {
      switch (action.name) {
        case 'highlight':
          nodes.forEach(function (n) {
            n.classList.add('vx-action-highlight');
            setTimeout(function () { n.classList.remove('vx-action-highlight'); }, 4000);
          });
          return { ok: true, detail: nodes.length > 1 ? String(nodes.length) + ' elements' : '' };

        case 'scrollTo':
          nodes[0].scrollIntoView({ behavior: 'smooth', block: 'center' });
          return { ok: true, detail: '' };

        case 'setClass':
          nodes.forEach(function (n) {
            if (action.add)    n.classList.add(action.add);
            if (action.remove) n.classList.remove(action.remove);
          });
          return { ok: true, detail: nodes.length > 1 ? String(nodes.length) + ' elements' : '' };

        case 'setText':
          // Refused on form controls: setting .textContent on an input does nothing visible while
          // reporting success, which would tell the agent a value was entered when it was not.
          for (var i = 0; i < nodes.length; i++) {
            if (FILLABLE[nodes[i].tagName]) {
              return { ok: false, detail: 'that is a form field — use fill, not setText' };
            }
          }
          nodes.forEach(function (n) { n.textContent = action.text; });
          return { ok: true, detail: '' };

        case 'fill':
          var filled = 0;
          for (var j = 0; j < nodes.length; j++) {
            var el = nodes[j];
            if (!FILLABLE[el.tagName]) continue;
            el.value = action.value;
            // Both events, because frameworks listen for different ones and a value set without
            // them looks right on screen while the application still holds the old one.
            el.dispatchEvent(new Event('input',  { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            filled++;
          }
          if (!filled) return { ok: false, detail: 'not a field that can be filled' };
          return { ok: true, detail: filled > 1 ? String(filled) + ' fields' : '' };

        default:
          return { ok: false, detail: 'unsupported' };
      }
    } catch (err) {
      return { ok: false, detail: err.message };
    }
  }

  /** Reads the ```vortox-actions block, if the model emitted one. */
  function parseActions(raw) {
    var m = /```vortox-actions\s*([\s\S]*?)```/.exec(raw);
    if (!m) return null;
    var parsed;
    try {
      parsed = JSON.parse(m[1].trim());
    } catch (e) {
      return { error: 'the assistant proposed page changes but they were not readable' };
    }
    var list = parsed && parsed.actions;
    if (!Array.isArray(list) || !list.length) return null;
    // A cap, because a runaway list of hundreds is a malfunction rather than an intention.
    if (list.length > 20) return { error: 'too many page changes proposed at once' };
    return { actions: list };
  }

  /**
   * Renders the action card and executes what it is allowed to.
   *
   * Anything needing approval is held until the operator clicks Apply. Approval is the only guard
   * that speaks to intent: permissions establish that the operator *may* do this, never that they
   * *meant* it — and an injected instruction typically asks for something they are entitled to do.
   */
  function handlePageActions(raw, containerEl) {
    var parsed = parseActions(raw);
    if (!parsed) return;

    var card = document.createElement('div');
    card.className = 'vx-actions';
    var head = document.createElement('div');
    head.className = 'vx-actions-head';
    card.appendChild(head);

    if (parsed.error) {
      head.textContent = 'Nothing ran';
      var bad = document.createElement('div');
      bad.className = 'vx-actions-note vx-actions-note-stop';
      bad.textContent = parsed.error;
      card.appendChild(bad);
      containerEl.appendChild(card);
      lastActionReport = 'Your proposed page changes could not be read, so nothing ran.';
      return;
    }

    var rows = [];
    var needsApproval = false;

    parsed.actions.forEach(function (action) {
      var problem = validateAction(action);
      var row = { action: action, problem: problem, el: null, stateEl: null };
      if (!problem && PAGE_ACTION_DEFS[action.name].confirm) needsApproval = true;
      rows.push(row);
    });

    var list = document.createElement('div');
    list.className = 'vx-actions-list';
    rows.forEach(function (row) {
      var el = document.createElement('div');
      el.className = 'vx-action-row';
      var name = document.createElement('span');
      name.className = 'vx-action-name';
      name.textContent = row.action && row.action.name ? String(row.action.name) : '?';
      var args = document.createElement('span');
      args.className = 'vx-action-args';
      args.textContent = describeArgs(row.action);
      var state = document.createElement('span');
      state.className = 'vx-action-state';
      if (row.problem) {
        state.textContent = 'blocked';
        state.className += ' vx-state-stop';
      }
      el.appendChild(name); el.appendChild(args); el.appendChild(state);
      list.appendChild(el);
      row.el = el; row.stateEl = state;
    });
    card.appendChild(list);
    containerEl.appendChild(card);

    var applyAll = function () {
      var report = [];
      rows.forEach(function (row) {
        if (row.problem) {
          report.push(row.action && row.action.name ? row.action.name + ': refused — ' + row.problem
                                                    : 'refused — ' + row.problem);
          return;
        }
        var result = runAction(row.action);
        row.stateEl.className = 'vx-action-state ' + (result.ok ? 'vx-state-ok' : 'vx-state-warn');
        row.stateEl.textContent = result.ok ? ('✓' + (result.detail ? ' ' + result.detail : ''))
                                            : result.detail;
        report.push(row.action.name + ' ' + row.action.target + ': ' +
                    (result.ok ? 'applied' + (result.detail ? ' (' + result.detail + ')' : '')
                               : 'failed — ' + result.detail));
      });
      head.textContent = summarise(rows);
      lastActionReport = report.join('\n');
    };

    if (!needsApproval) {
      applyAll();
      return;
    }

    head.textContent = 'Waiting for approval · ' + rows.length +
                       (rows.length === 1 ? ' change' : ' changes');
    var foot = document.createElement('div');
    foot.className = 'vx-actions-foot';
    var apply = document.createElement('button');
    apply.type = 'button';
    apply.className = 'vx-action-btn vx-action-btn-primary';
    apply.textContent = 'Apply';
    var dismiss = document.createElement('button');
    dismiss.type = 'button';
    dismiss.className = 'vx-action-btn';
    dismiss.textContent = 'Dismiss';
    var note = document.createElement('span');
    note.className = 'vx-actions-runas';
    note.textContent = 'runs as you';
    foot.appendChild(apply); foot.appendChild(dismiss); foot.appendChild(note);
    card.appendChild(foot);

    apply.addEventListener('click', function () {
      foot.parentNode.removeChild(foot);
      applyAll();
    });
    dismiss.addEventListener('click', function () {
      foot.parentNode.removeChild(foot);
      head.textContent = 'Dismissed';
      rows.forEach(function (row) {
        if (row.problem) return;
        row.stateEl.textContent = 'not applied';
        row.stateEl.className = 'vx-action-state vx-state-muted';
      });
      // Recorded so the agent knows the operator declined rather than that it silently worked.
      lastActionReport = 'The user reviewed your proposed page changes and dismissed them. ' +
                         'Nothing was applied. Do not simply repeat the same proposal.';
    });
  }

  function describeArgs(action) {
    if (!action || typeof action !== 'object') return '';
    var bits = [];
    if (action.target) bits.push(String(action.target));
    if (action.value !== undefined)  bits.push('→ "' + action.value + '"');
    if (action.text !== undefined)   bits.push('→ "' + action.text + '"');
    if (action.add)    bits.push('+' + action.add);
    if (action.remove) bits.push('−' + action.remove);
    return bits.join(' ');
  }

  /** Plain-language verdict — "1 error" is not something an operator can act on. */
  function summarise(rows) {
    var total = rows.length;
    var ok = 0, failed = 0;
    rows.forEach(function (row) {
      if (row.problem) { failed++; return; }
      if (row.stateEl && row.stateEl.className.indexOf('vx-state-ok') !== -1) ok++;
      else failed++;
    });
    if (failed === 0) return 'Page updated';
    if (ok === 0)     return 'Nothing ran';
    return ok + ' of ' + total + ' applied';
  }

  // ── Downloadable artifacts ────────────────────────────────────────────────────
  // Files the agent generated (e.g. reports) come back inlined as base64 on the
  // chat response — no server-side storage involved, the bytes just live in this
  // message's memory for as long as the widget session is open.

  function fmtSize(n) {
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
  }

  function downloadArtifact(artifact) {
    try {
      var binary = atob(artifact.base64);
      var bytes = new Uint8Array(binary.length);
      for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
      var blob = new Blob([bytes], { type: artifact.mimeType || 'application/octet-stream' });
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url;
      a.download = artifact.filename || 'download';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
    } catch (err) {
      /* eslint-disable-next-line no-console */
      console.error('Vortox widget: failed to download artifact', err);
    }
  }

  function renderArtifacts(artifacts, containerEl) {
    if (!artifacts || !artifacts.length) return;
    artifacts.forEach(function (artifact) {
      var chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'vx-artifact';
      chip.innerHTML =
        '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke-width="2" ' +
        'stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12m0 0l-4-4m4 4l4-4M5 21h14"/></svg>' +
        '<span>' + esc(artifact.filename || 'download') + '</span>' +
        '<span class="vx-artifact-size">' + fmtSize(artifact.sizeBytes || 0) + '</span>';
      chip.addEventListener('click', function () { downloadArtifact(artifact); });
      containerEl.appendChild(chip);
    });
  }

  // ── DOM ───────────────────────────────────────────────────────────────────────

  var fab = document.createElement('button');
  fab.id = 'vx-fab';
  fab.title = TITLE;
  fab.setAttribute('aria-label', 'Open AI assistant');
  fab.innerHTML = '<svg viewBox="0 0 24 24"><path d="M8 9h8M8 13h5"/>' +
    '<path d="M20 2H4a2 2 0 0 0-2 2v14l4-4h14a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2z"/></svg>';

  var panel = document.createElement('div');
  panel.id = 'vx-panel';
  panel.setAttribute('role', 'dialog');
  panel.setAttribute('aria-label', TITLE);
  var MAXIMIZE_ICON = '<svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/>' +
    '<line x1="14" y1="4" x2="14" y2="20"/></svg>';
  var RESTORE_ICON  = '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>';

  panel.innerHTML =
    '<div id="vx-header">' +
      '<div style="display:flex;align-items:center">' +
        '<div id="vx-header-dot"></div>' +
        '<span id="vx-header-title">' + esc(TITLE) + '</span>' +
      '</div>' +
      '<div id="vx-header-actions">' +
        '<button id="vx-minimize" class="vx-header-btn" aria-label="Minimize" title="Minimize">&minus;</button>' +
        '<button id="vx-maximize" class="vx-header-btn" aria-label="Expand to side panel" title="Expand to side panel">' +
          MAXIMIZE_ICON +
        '</button>' +
        '<button id="vx-close" class="vx-header-btn" aria-label="Close" title="Close">\xd7</button>' +
      '</div>' +
    '</div>' +
    '<div id="vx-context" hidden></div>' +
    '<div id="vx-messages" role="log" aria-live="polite"></div>' +
    '<div id="vx-suggestions"></div>' +
    '<div id="vx-form">' +
      '<textarea id="vx-input" rows="1" placeholder="Ask anything… (Shift+Enter for new line)" autocomplete="off"></textarea>' +
      '<button id="vx-send">Send</button>' +
    '</div>';

  document.body.appendChild(fab);
  document.body.appendChild(panel);

  var messagesEl    = document.getElementById('vx-messages');
  var contextEl     = document.getElementById('vx-context');
  var suggestionsEl = document.getElementById('vx-suggestions');

  // ── Context indicator ─────────────────────────────────────────────────────────
  // A row of chips naming what the agent was actually told about this page and user.
  // Rendered from the server's own report of the outgoing request (see
  // describeEffectiveContext), not from what this widget assembled: the host's enricher
  // adds the authenticated user and institution the page never sees, and can strip
  // fields the page did send. A badge that guessed would eventually claim something
  // untrue about what left the browser.
  //
  // Clicking one shows exactly what it contributed, so "what does this thing know about
  // me?" has a concrete answer instead of requiring trust.

  var CONTEXT_LABELS = {
    authenticatedUser: { icon: '👤', label: 'User' },
    institutionCode:   { icon: '🏛', label: 'Institution' },
    tenant:            { icon: '🏛', label: 'Tenant' },
    application:       { icon: '📦', label: 'App' },
    surface:           { icon: '📄', label: 'Screen' },
    pageContext:       { icon: '🔎', label: 'Page' },
    pageActions:       { icon: '✋', label: 'Actions' },
    pageScripts:       { icon: '⚠️', label: 'Scripts' }
  };

  function renderContextChips(summary) {
    if (!contextEl) return;
    contextEl.innerHTML = '';
    if (!summary || !summary.length) { contextEl.hidden = true; return; }

    summary.forEach(function (item) {
      if (!item || !item.key) return;
      var meta = CONTEXT_LABELS[item.key] || { icon: '•', label: item.key };
      var value = String(item.value == null ? '' : item.value);

      var chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'vx-ctx-chip';
      // "not sent" is worth showing as a distinct state rather than hiding the chip: absent
      // page context is a fact about the conversation, not an absence of information.
      if (/^(not sent|disabled|none)$/i.test(value)) chip.className += ' vx-ctx-off';
      if (item.key === 'pageScripts') chip.className += ' vx-ctx-warn';

      var short = value.length > 22 ? value.substring(0, 21) + '…' : value;
      chip.textContent = meta.icon + ' ' + short;
      chip.title = meta.label + ': ' + value +
                   (item.source ? '  (added by the ' + item.source + ')' : '');
      chip.addEventListener('click', function () {
        appendMessage('agent', '**' + meta.label + '** — ' + value +
                      (item.source ? '\n\nAdded by the ' + item.source + '.' : ''));
      });
      contextEl.appendChild(chip);
    });
    contextEl.hidden = false;
  }
  var inputEl       = document.getElementById('vx-input');
  var sendBtn       = document.getElementById('vx-send');
  var maximizeBtn   = document.getElementById('vx-maximize');

  // ── Suggestions ───────────────────────────────────────────────────────────────

  function renderSuggestions() {
    suggestionsEl.innerHTML = '';
    if (history.length > 0) return;
    SUGGESTIONS.forEach(function (text) {
      var chip = document.createElement('button');
      chip.className = 'vx-chip';
      chip.textContent = text;
      chip.addEventListener('click', function () { sendMessage(text); });
      suggestionsEl.appendChild(chip);
    });
  }

  // ── Messages ──────────────────────────────────────────────────────────────────

  function appendMessage(role, text) {
    var div = document.createElement('div');
    div.className = role === 'user' ? 'vx-msg-user' : 'vx-msg-agent';
    if (role === 'user') {
      div.textContent = text;
    } else {
      div.innerHTML = renderMarkdown(text);
    }
    messagesEl.appendChild(div);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return div;
  }

  function showThinking() {
    steps = [];
    stepsExpanded = false;
    var div = document.createElement('div');
    div.className = 'vx-thinking';
    div.id = 'vx-thinking';
    div.innerHTML =
      '<div class="vx-thinking-header">' +
        '<div class="vx-dot"></div><div class="vx-dot"></div><div class="vx-dot"></div>' +
        '<span class="vx-thinking-label" id="vx-thinking-label">Thinking…</span>' +
      '</div>' +
      '<div class="vx-steps" id="vx-steps"></div>';
    messagesEl.appendChild(div);
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function hideThinking() {
    var el = document.getElementById('vx-thinking');
    if (el) el.parentNode.removeChild(el);
  }

  /** Updates the short status text next to the bouncing dots (e.g. "Thinking…" / "Working…").
   *  No-op if the thinking bubble isn't showing (e.g. a late event arrives after completion). */
  function setThinkingHeader(text) {
    var label = document.getElementById('vx-thinking-label');
    if (label) label.textContent = text;
  }

  /** Text for a step row: the label, how many calls it stands for, and how many of them failed.
   *  The failure note is only added when there were failures, so a tool legitimately called
   *  several times in a row reads as "×3" rather than as three things going wrong. */
  function stepText(step) {
    var n = step.attempts || 1;
    var failures = step.failures || 0;
    var text = step.label + (n > 1 ? ' ×' + n : '');
    if (step.status === 'running') return text + '…';
    if (step.status === 'failed') {
      return n > 1 ? text + ' · ' + failures + ' of ' + n + ' failed' : text;
    }
    if (failures > 0) {
      text += ' · succeeded after ' + failures + (failures === 1 ? ' failure' : ' failures');
    }
    return text;
  }

  /** The second line: what this step acted on, plus how long it took once known. */
  function stepDetail(step) {
    var parts = [];
    if (step.detail) parts.push(step.detail);
    if (step.status !== 'running' && step.durationMs != null && step.durationMs >= 1000) {
      parts.push(Math.round(step.durationMs / 100) / 10 + 's');
    }
    return parts.join('  ·  ');
  }

  /** Icon per state. Glyphs rather than colour alone, so state survives a colourblind reader. */
  function stepIcon(status) {
    return status === 'done' ? '✓' : status === 'failed' ? '✕' : '●';
  }

  /** Paints one step's row from its current state. */
  function paintStep(step) {
    if (!step.rowEl) return;
    step.rowEl.className = 'vx-step vx-step-' + step.status;
    step.iconEl.textContent = stepIcon(step.status);
    step.titleEl.textContent = stepText(step);
    var detail = stepDetail(step);
    step.detailEl.textContent = detail;
    step.detailEl.style.display = detail ? '' : 'none';
  }

  /**
   * Keeps the live trace short by folding all but the last few entries behind a count.
   *
   * A long run can make dozens of tool calls; showing every one pushes the reply out of view, and
   * putting them in a scroll box (as this once did) makes a cramped region that hides the history
   * it exists to show. The recent steps are what a waiting user reads, and the rest stay one click
   * away.
   */
  var STEPS_VISIBLE = 4;

  function reflowSteps() {
    var stepsEl = document.getElementById('vx-steps');
    if (!stepsEl) return;
    var hidden = stepsExpanded ? 0 : Math.max(0, steps.length - STEPS_VISIBLE);

    steps.forEach(function (s, i) {
      if (s.rowEl) s.rowEl.style.display = i < hidden ? 'none' : '';
    });

    var moreEl = document.getElementById('vx-step-more');
    if (!hidden && !stepsExpanded) { if (moreEl) moreEl.remove(); return; }
    if (!moreEl) {
      moreEl = document.createElement('button');
      moreEl.type = 'button';
      moreEl.id = 'vx-step-more';
      moreEl.className = 'vx-step-more';
      moreEl.addEventListener('click', function () {
        stepsExpanded = !stepsExpanded;
        reflowSteps();
      });
      stepsEl.insertBefore(moreEl, stepsEl.firstChild);
    }
    moreEl.textContent = stepsExpanded
      ? 'Show less'
      : '↑ ' + hidden + ' earlier step' + (hidden === 1 ? '' : 's');
  }

  /** Appends a new "in progress" row to the live step trace for a tool that just started.
   *
   *  Consecutive calls of the same tool collapse into the row already there. The agent retrying a
   *  failed query, or refining and re-running one, is a single piece of work — eight identical
   *  rows report it eight times and read as eight separate things going wrong. One row carrying an
   *  attempt count says what actually happened, and says it in the space of a line. Runs of a
   *  different tool in between still start a new row, so the order of work stays visible. */
  function addStep(tool, detail) {
    var previous = steps.length ? steps[steps.length - 1] : null;
    // Only fold a repeat into the row above when it is the same work on the same thing. Two
    // queries against different tables are two steps however adjacent they are — folding those
    // was what produced a trace of identical rows that said nothing.
    if (previous && previous.tool === tool && previous.status !== 'running'
        && (previous.detail || '') === (detail || '')) {
      previous.attempts = (previous.attempts || 1) + 1;
      previous.status = 'running';
      previous.durationMs = null;
      paintStep(previous);
      messagesEl.scrollTop = messagesEl.scrollHeight;
      return;
    }

    var step = { tool: tool, label: humanizeTool(tool), detail: detail || '',
                 status: 'running', attempts: 1, failures: 0, durationMs: null };
    var stepsEl = document.getElementById('vx-steps');
    if (stepsEl) {
      var row = document.createElement('div');
      var icon = document.createElement('span');
      icon.className = 'vx-step-icon';
      var body = document.createElement('div');
      body.className = 'vx-step-body';
      var title = document.createElement('div');
      title.className = 'vx-step-title';
      var det = document.createElement('div');
      det.className = 'vx-step-detail';
      body.appendChild(title);
      body.appendChild(det);
      row.appendChild(icon);
      row.appendChild(body);
      stepsEl.appendChild(row);
      step.rowEl = row; step.iconEl = icon; step.titleEl = title; step.detailEl = det;
      paintStep(step);
    }
    steps.push(step);
    reflowSteps();
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  /** Resolves the most recent still-running step for this tool to done/failed. */
  function resolveStep(tool, success, durationMs) {
    for (var i = steps.length - 1; i >= 0; i--) {
      if (steps[i].tool === tool && steps[i].status === 'running') {
        steps[i].status = success ? 'done' : 'failed';
        steps[i].durationMs = durationMs;
        if (!success) steps[i].failures = (steps[i].failures || 0) + 1;
        paintStep(steps[i]);
        return;
      }
    }
  }

  function fmtElapsed(ms) {
    var s = Math.round(ms / 1000);
    if (s < 60) return s + 's';
    return Math.floor(s / 60) + 'm ' + (s % 60) + 's';
  }

  /** Renders the collapsed "✓ N steps · Xs" pill above the reply once a run completes — click
   *  to expand and review the full trace (Claude Desktop's "Thought for Xs" pattern). No-op for
   *  runs with no recorded steps (trivial single-shot replies, or transports with no SSE trace). */
  function renderStepSummary(elapsedMs) {
    if (!steps.length) return;

    // Counted over attempts, not rows: rows collapse consecutive calls of the same tool, so
    // steps.length would under-report both the work done and the failures along the way.
    var totalCalls = steps.reduce(function (n, s) { return n + (s.attempts || 1); }, 0);
    var failedCount = steps.reduce(function (n, s) { return n + (s.failures || 0); }, 0);
    var label = totalCalls + (totalCalls === 1 ? ' step' : ' steps');
    if (failedCount) label += ', ' + failedCount + ' failed';

    var wrap = document.createElement('div');
    wrap.className = 'vx-summary-wrap';

    var pill = document.createElement('button');
    pill.type = 'button';
    pill.className = 'vx-summary';
    pill.innerHTML = '<span class="vx-summary-chevron">▶</span><span>' +
      (failedCount ? '⚠' : '✓') + ' ' + esc(label) + ' · ' + fmtElapsed(elapsedMs) + '</span>';

    var detail = document.createElement('div');
    detail.className = 'vx-summary-detail';
    steps.forEach(function (s) {
      var row = document.createElement('div');
      row.className = 'vx-step vx-step-' + s.status;
      // Same two-line shape as the live trace, so expanding the summary after the fact shows
      // exactly what was on screen while the run was going.
      var summaryDetail = stepDetail(s);
      row.innerHTML = '<span class="vx-step-icon">' + stepIcon(s.status) + '</span>' +
        '<div class="vx-step-body"><div class="vx-step-title">' + esc(stepText(s)) + '</div>' +
        (summaryDetail ? '<div class="vx-step-detail">' + esc(summaryDetail) + '</div>' : '') +
        '</div>';
      detail.appendChild(row);
    });

    pill.addEventListener('click', function () {
      var open = detail.classList.toggle('vx-summary-detail-open');
      pill.classList.toggle('vx-summary-open', open);
    });

    wrap.appendChild(pill);
    wrap.appendChild(detail);
    messagesEl.appendChild(wrap);
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  // ── Send ──────────────────────────────────────────────────────────────────────

  function sendMessage(text) {
    text = (text || '').trim();
    if (!text || isThinking) return;

    suggestionsEl.innerHTML = '';
    appendMessage('user', text);
    history.push({ role: 'user', content: text });

    isThinking = true;
    sendBtn.disabled = true;
    inputEl.value = '';
    inputEl.style.height = 'auto';
    runStartedAt = Date.now();
    showThinking();

    var payload = {
      message: text,
      history: history.slice(0, -1),
      context: Object.assign({}, getContext(), _pageCtx)
    };

    // Which screen this is, so the agent's instructions can differ per screen. A plain identifier,
    // not a description: the prose about the page already travels in `context`. Omitted entirely
    // when the host has not named its screens, which reads as "the application default".
    if (SURFACE) { payload.surface = SURFACE; }
    if (TENANT_CODE) { payload.tenantCode = TENANT_CODE; }

    // One place builds the snapshot, whichever capability wanted it. It used to be assembled
    // separately inside each branch, so the two could drift and neither said what happened when
    // both were off — the read-only case had no way to exist at all.
    if (SEND_PAGE_CONTEXT) {
      var domSnapshot = collectPageDom();
      payload.pageApiDescription = PAGE_API_DESCRIPTION
        ? domSnapshot + '\n\n### Host-provided notes\n' + PAGE_API_DESCRIPTION
        : domSnapshot;
    } else if (PAGE_API_DESCRIPTION) {
      // The host described its page by hand and does not want it introspected: honour both.
      payload.pageApiDescription = PAGE_API_DESCRIPTION;
    }

    if (PAGE_ACTIONS_ENABLED) {
      payload.pageActions = Object.keys(PAGE_ACTION_DEFS);
      if (lastActionReport) {
        payload.lastActionResults = lastActionReport;
        lastActionReport = null;
      }
    }

    if (ALLOW_PAGE_SCRIPTS) {
      payload.allowPageScripts = true;
    }

    postJson(ENDPOINT, payload, function (err, data) {
      if (err) { finishWithError(err); return; }
      if (!data) { finishWithError('Could not start the agent.'); return; }
      // Present on the start response whichever transport follows, and it describes the request
      // that was just sent — so the chips update per message rather than showing a stale first turn.
      if (data.contextSummary) renderContextChips(data.contextSummary);

      if (data.runId) {
        var startedAt = Date.now();
        // Prefer live progress over blind polling when the browser supports it. Any failure to
        // establish or maintain the stream falls back to the exact same pollRun() used when
        // EventSource isn't available at all — polling remains the source of truth either way.
        if (typeof window.EventSource === 'function') {
          openStream(data.runId, startedAt);
        } else {
          pollRun(data.runId, startedAt);
        }
        return;
      }
      // A same-origin proxy (e.g. a server-side controller sitting between this widget and the
      // sidecar) may absorb the polling itself and hand back the finished {reply, artifacts} in
      // this one response instead of {runId, status}. Render immediately in that case — there's
      // nothing to poll. (If the proxy's own wait budget can exceed REQUEST_TIMEOUT_MS, the host
      // page should raise cfg.requestTimeoutMs accordingly.)
      if (data.reply !== undefined || data.artifacts !== undefined) {
        finishWithReply(data.reply || 'No response from agent.', data.artifacts);
        return;
      }
      finishWithError(data.error || 'Could not start the agent.');
    });
  }

  // ── Live progress via SSE, with polling fallback ─────────────────────────────────
  // Opens GET {ENDPOINT}/{runId}/stream and renders iteration/tool events into the
  // thinking bubble in place. Falls back to pollRun() — reusing the same startedAt so
  // MAX_WAIT_MS is one continuous ceiling regardless of which transport ends up serving
  // the request — if: EventSource throws on construction, a transport-level error fires
  // before a 'done' event is seen, or MAX_WAIT_MS elapses with no 'done' yet.

  function openStream(runId, startedAt) {
    var es;
    try {
      es = new EventSource(buildStreamUrl(runId));
    } catch (e) {
      pollRun(runId, startedAt);
      return;
    }

    var settled = false;

    var watchdog = setTimeout(function () {
      if (settled) return;
      fallBackToPoll();
    }, Math.max(0, startedAt + MAX_WAIT_MS - Date.now()));

    function fallBackToPoll() {
      if (settled) return;
      settled = true;
      clearTimeout(watchdog);
      try { es.close(); } catch (e) { /* ignore */ }
      pollRun(runId, startedAt);
    }

    // Raw iteration counts ("step 3 of 75") are internal loop-budget noise, not something an
    // end user should have to make sense of — Claude Code/Desktop don't surface that either.
    // The header just alternates between the two phases of a ReAct step; the actual visible
    // trail of what happened is the growing step list (addStep/resolveStep) below it.
    es.addEventListener('iteration', function () {
      setThinkingHeader('Thinking…');
    });

    es.addEventListener('tool_call', function (ev) {
      try {
        var d = JSON.parse(ev.data);
        setThinkingHeader('Working…');
        addStep(d.tool, d.detail);
      } catch (e) { /* ignore malformed event */ }
    });

    es.addEventListener('tool_result', function (ev) {
      try {
        var d = JSON.parse(ev.data);
        resolveStep(d.tool, d.success, d.durationMs);
      } catch (e) { /* ignore malformed event */ }
    });

    // A server-pushed application-level error (has ev.data, e.g. a tool timeout) is just
    // informational — the run is still going, so leave the stream open. Only the browser's
    // own transport-level error (no ev.data at all) means the connection itself broke.
    es.addEventListener('error', function (ev) {
      if (ev && ev.data) return;
      fallBackToPoll();
    });

    es.addEventListener('done', function () {
      if (settled) return;
      settled = true;
      clearTimeout(watchdog);
      try { es.close(); } catch (e) { /* ignore */ }
      // One authoritative GET for the final result — keeps the existing poll endpoint as the
      // single source of truth for {reply, artifacts} instead of duplicating that (potentially
      // large, base64-artifact-bearing) payload through the SSE channel too.
      getJson(buildPollUrl(runId), function (err, data) {
        if (err) { finishWithError('Lost track of the agent run.'); return; }
        if (!data || data.status === 'NOT_FOUND') {
          finishWithError('Lost track of the agent run (the sidecar may have restarted). Please try again.');
          return;
        }
        if (data.status === 'ERROR') { finishWithError(data.error || 'The agent hit an error.'); return; }
        finishWithReply(data.reply || 'No response from agent.', data.artifacts);
      });
    });
  }

  // ── Poll for completion ─────────────────────────────────────────────────────────

  function pollRun(runId, startedAt) {
    if (Date.now() - startedAt > MAX_WAIT_MS) {
      finishWithError('The agent took too long to respond. Try a simpler query.');
      return;
    }
    getJson(buildPollUrl(runId), function (err, data) {
      if (err) {
        // Transient network hiccup while polling — keep trying until MAX_WAIT_MS.
        setTimeout(function () { pollRun(runId, startedAt); }, POLL_INTERVAL_MS);
        return;
      }
      if (!data || data.status === 'NOT_FOUND') {
        finishWithError('Lost track of the agent run (the sidecar may have restarted). Please try again.');
        return;
      }
      if (data.status === 'RUNNING') {
        setTimeout(function () { pollRun(runId, startedAt); }, POLL_INTERVAL_MS);
        return;
      }
      if (data.status === 'ERROR') {
        finishWithError(data.error || 'The agent hit an error.');
        return;
      }
      finishWithReply(data.reply || 'No response from agent.', data.artifacts);
    });
  }

  function finishWithReply(reply, artifacts) {
    hideThinking();
    renderStepSummary(Date.now() - runStartedAt);
    isThinking = false;
    sendBtn.disabled = false;
    var msgEl = appendMessage('agent', reply);
    history.push({ role: 'assistant', content: reply });
    if (ALLOW_PAGE_SCRIPTS) extractAndRunScripts(reply, msgEl);
    if (PAGE_ACTIONS_ENABLED) handlePageActions(reply, msgEl);
    renderArtifacts(artifacts, msgEl);
  }

  function finishWithError(message) {
    hideThinking();
    renderStepSummary(Date.now() - runStartedAt);
    isThinking = false;
    sendBtn.disabled = false;
    appendMessage('agent', message);
  }

  // ── Small XHR helpers (each call is short-lived — the run itself is polled) ────

  function postJson(url, body, cb) {
    var xhr = new XMLHttpRequest();
    xhr.open('POST', url, true);
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.timeout = REQUEST_TIMEOUT_MS;
    xhr.onload    = function () { parseJsonResponse(xhr, cb); };
    xhr.onerror   = function () { cb('Could not reach the agent. Check that the sidecar container is running.'); };
    xhr.ontimeout = function () { cb('The agent did not respond in time.'); };
    xhr.send(JSON.stringify(body));
  }

  function getJson(url, cb) {
    var xhr = new XMLHttpRequest();
    xhr.open('GET', url, true);
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.timeout = REQUEST_TIMEOUT_MS;
    xhr.onload    = function () { parseJsonResponse(xhr, cb); };
    xhr.onerror   = function () { cb('network error'); };
    xhr.ontimeout = function () { cb('timeout'); };
    xhr.send();
  }

  function parseJsonResponse(xhr, cb) {
    try {
      cb(null, JSON.parse(xhr.responseText));
    } catch (e) {
      cb('Unexpected response from agent.');
    }
  }

  // ── Events ────────────────────────────────────────────────────────────────────

  function closePanel() {
    isOpen = false;
    panel.classList.remove('vx-open');
  }

  /** Toggles between the small floating card and a full-height panel docked to the right
   *  edge of the viewport. Independent of open/closed state — reopening (via the FAB) after
   *  a maximize keeps whichever size was last chosen. */
  function setMaximized(next) {
    isMaximized = next;
    panel.classList.toggle('vx-maximized', isMaximized);
    maximizeBtn.innerHTML = isMaximized ? RESTORE_ICON : MAXIMIZE_ICON;
    var label = isMaximized ? 'Restore' : 'Expand to side panel';
    maximizeBtn.title = label;
    maximizeBtn.setAttribute('aria-label', label);
  }

  fab.addEventListener('click', function () {
    isOpen = !isOpen;
    panel.classList.toggle('vx-open', isOpen);
    if (isOpen) {
      renderSuggestions();
      setTimeout(function () { inputEl.focus(); }, 200);
    }
  });

  document.getElementById('vx-close').addEventListener('click', closePanel);
  document.getElementById('vx-minimize').addEventListener('click', closePanel);
  maximizeBtn.addEventListener('click', function () { setMaximized(!isMaximized); });

  sendBtn.addEventListener('click', function () { sendMessage(inputEl.value); });

  inputEl.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage(inputEl.value);
    }
  });

  inputEl.addEventListener('input', function () {
    inputEl.style.height = 'auto';
    inputEl.style.height = Math.min(inputEl.scrollHeight, 120) + 'px';
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && isOpen) closePanel();
  });

  // ── Public API ────────────────────────────────────────────────────────────────
  // VortoxAgent.setContext('key', value) — inject page-specific context into every request
  // VortoxAgent.setSuggestions([...])   — replace the suggestion chips
  cfg.setContext    = function (key, value) { _pageCtx[key] = value; };
  cfg.setSuggestions = function (suggestions) { SUGGESTIONS = suggestions; };
  // For applications that navigate without reloading the page, where the widget outlives the screen
  // it was configured on and would otherwise keep reporting the first one it ever saw.
  cfg.setSurface    = function (surface) { SURFACE = surface || ''; };

})();
