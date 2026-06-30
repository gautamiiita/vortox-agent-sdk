(function () {
  'use strict';

  var cfg = window.VortoxAgent || {};
  var ENDPOINT             = cfg.endpoint            || '/agent/chat';
  var TITLE                = cfg.title               || 'AI Assistant';
  var SUGGESTIONS          = cfg.suggestions          || [];
  var ALLOW_PAGE_SCRIPTS   = cfg.allowPageScripts     === true;
  var PAGE_API_DESCRIPTION = cfg.pageApiDescription   || '';
  var getContext           = typeof cfg.context === 'function' ? cfg.context : function () { return {}; };

  var _pageCtx   = {};
  var history    = [];
  var isOpen     = false;
  var isThinking = false;

  // ── Styles ────────────────────────────────────────────────────────────────────

  var style = document.createElement('style');
  style.textContent = [
    /* FAB */
    '#vx-fab{position:fixed;bottom:24px;right:24px;width:52px;height:52px;background:#1a3a6b;',
    'border:none;border-radius:10px;cursor:pointer;display:flex;align-items:center;justify-content:center;',
    'box-shadow:0 4px 16px rgba(0,0,0,0.35);z-index:9998;transition:background 0.15s;}',
    '#vx-fab:hover{background:#2351a0;}',
    '#vx-fab svg{width:24px;height:24px;fill:none;stroke:#e8f0ff;stroke-width:2;stroke-linecap:round;stroke-linejoin:round;}',

    /* Panel */
    '#vx-panel{position:fixed;bottom:88px;right:24px;width:440px;max-width:calc(100vw - 32px);height:560px;',
    'background:#07090f;border:1px solid #1a2744;border-radius:12px;',
    'box-shadow:0 8px 32px rgba(0,0,0,0.55);z-index:9999;display:flex;flex-direction:column;',
    'opacity:0;transform:translateY(12px);pointer-events:none;',
    'transition:opacity 0.2s cubic-bezier(.4,0,.2,1),transform 0.2s cubic-bezier(.4,0,.2,1);}',
    '#vx-panel.vx-open{opacity:1;transform:translateY(0);pointer-events:auto;}',

    /* Header */
    '#vx-header{padding:14px 16px;border-bottom:1px solid #1a2744;display:flex;',
    'align-items:center;justify-content:space-between;flex-shrink:0;}',
    '#vx-header-title{color:#c8d8ff;font-family:monospace;font-size:13px;font-weight:600;letter-spacing:.04em;}',
    '#vx-header-dot{width:8px;height:8px;border-radius:50%;background:#2f7cf6;margin-right:8px;',
    'box-shadow:0 0 6px #2f7cf6;animation:vx-pulse 2s ease-in-out infinite;}',
    '@keyframes vx-pulse{0%,100%{opacity:1}50%{opacity:.4}}',
    '#vx-close{background:none;border:none;color:#4a6090;cursor:pointer;font-size:18px;padding:0;line-height:1;}',
    '#vx-close:hover{color:#c8d8ff;}',

    /* Messages */
    '#vx-messages{flex:1;overflow-y:auto;padding:12px 14px;display:flex;flex-direction:column;gap:10px;min-height:0;}',
    '#vx-messages::-webkit-scrollbar{width:4px;}',
    '#vx-messages::-webkit-scrollbar-track{background:transparent;}',
    '#vx-messages::-webkit-scrollbar-thumb{background:#1a2744;border-radius:2px;}',

    /* Bubbles */
    '.vx-msg-agent{align-self:flex-start;max-width:100%;color:#c8d8ff;font-family:monospace;font-size:12px;',
    'line-height:1.6;border-left:3px solid #2f7cf6;padding:8px 12px;background:#0a1225;border-radius:0 6px 6px 0;',
    'overflow-x:auto;word-break:break-word;}',
    '.vx-msg-user{align-self:flex-end;max-width:82%;color:#e8f0ff;font-family:monospace;font-size:12px;',
    'line-height:1.5;background:#0f2456;padding:8px 12px;border-radius:10px 10px 2px 10px;word-break:break-word;}',

    /* Markdown: paragraphs, headers, hr */
    '.vx-msg-agent .vx-p{margin:0 0 8px 0;}',
    '.vx-msg-agent .vx-p:last-child{margin-bottom:0;}',
    '.vx-msg-agent .vx-h{color:#7ba8f0;font-family:monospace;font-size:12px;font-weight:700;',
    'margin:10px 0 4px;letter-spacing:.03em;text-transform:uppercase;}',
    '.vx-msg-agent .vx-hr{border:none;border-top:1px solid #1a2744;margin:8px 0;}',

    /* Markdown: lists */
    '.vx-msg-agent .vx-ul,.vx-msg-agent .vx-ol{margin:4px 0 8px 16px;padding:0;}',
    '.vx-msg-agent .vx-li{margin-bottom:3px;}',

    /* Markdown: code */
    '.vx-msg-agent .vx-ic{background:#040609;border:1px solid #1a2744;border-radius:3px;',
    'padding:1px 4px;font-family:monospace;font-size:11px;color:#86efac;}',
    '.vx-msg-agent .vx-pre{background:#040609;border:1px solid #1a2744;border-radius:6px;',
    'padding:10px 12px;margin:6px 0;overflow-x:auto;white-space:pre;}',
    '.vx-msg-agent .vx-pre code{font-family:monospace;font-size:11px;color:#86efac;background:none;',
    'border:none;padding:0;}',

    /* Markdown: tables */
    '.vx-msg-agent .vx-tw{overflow-x:auto;margin:6px 0;border-radius:6px;}',
    '.vx-msg-agent .vx-table{border-collapse:collapse;width:100%;font-size:11px;font-family:monospace;}',
    '.vx-msg-agent .vx-th{background:#0f2456;color:#7ba8f0;padding:5px 10px;',
    'border:1px solid #1a2744;text-align:left;font-weight:700;white-space:nowrap;}',
    '.vx-msg-agent .vx-td{color:#c8d8ff;padding:4px 10px;border:1px solid #1a2744;}',
    '.vx-msg-agent .vx-tr:hover .vx-td{background:#0a1e40;}',

    /* Markdown: bold/italic */
    '.vx-msg-agent strong{color:#e8f0ff;font-weight:700;}',
    '.vx-msg-agent em{color:#a0c4f8;font-style:italic;}',

    /* Thinking */
    '.vx-thinking{align-self:flex-start;color:#4a6090;font-family:monospace;font-size:11px;',
    'padding:6px 10px;border-left:3px solid #1a2744;display:flex;gap:4px;align-items:center;}',
    '.vx-dot{width:5px;height:5px;border-radius:50%;background:#2f7cf6;animation:vx-bounce 1.2s ease-in-out infinite;}',
    '.vx-dot:nth-child(2){animation-delay:.2s}.vx-dot:nth-child(3){animation-delay:.4s}',
    '@keyframes vx-bounce{0%,80%,100%{transform:translateY(0)}40%{transform:translateY(-5px)}}',

    /* Suggestions */
    '#vx-suggestions{padding:0 14px 10px;display:flex;flex-wrap:wrap;gap:6px;flex-shrink:0;}',
    '.vx-chip{background:#0a1225;border:1px solid #1a2744;color:#6a90c8;',
    'font-family:monospace;font-size:11px;padding:5px 10px;border-radius:12px;cursor:pointer;',
    'white-space:nowrap;transition:border-color .15s,color .15s;}',
    '.vx-chip:hover{border-color:#2f7cf6;color:#c8d8ff;}',

    /* Input */
    '#vx-form{padding:10px 14px 14px;border-top:1px solid #1a2744;display:flex;gap:8px;flex-shrink:0;}',
    '#vx-input{flex:1;background:#0a1225 !important;border:1px solid #1a2744 !important;border-radius:8px;',
    'color:#ffffff !important;font-family:monospace !important;font-size:12px;padding:8px 12px;outline:none;',
    'transition:border-color .15s;box-shadow:none !important;}',
    '#vx-input:focus{border-color:#2f7cf6 !important;}',
    '#vx-input::placeholder{color:#3a5070 !important;}',
    '#vx-send{background:#1a3a6b;border:none;border-radius:8px;color:#e8f0ff;',
    'cursor:pointer;padding:8px 16px;font-family:monospace;font-size:12px;font-weight:600;',
    'transition:background .15s;white-space:nowrap;}',
    '#vx-send:hover:not(:disabled){background:#2351a0;}',
    '#vx-send:disabled{opacity:.4;cursor:default;}',

    /* Page-script execution feedback */
    '.vx-executed-badge{display:inline-block;margin-top:6px;padding:2px 8px;background:#061a06;',
    'border:1px solid #1a6b1a;border-radius:4px;color:#4ade80;font-family:monospace;font-size:10px;}',
    '.vx-script-error{display:block;margin-top:6px;padding:4px 8px;background:#1a0606;',
    'border:1px solid #6b1a1a;border-radius:4px;color:#f87171;font-family:monospace;font-size:10px;',
    'white-space:pre-wrap;word-break:break-all;}'
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
  // Automatically collects IDs, labelled form fields, and headings from the live
  // page so the LLM knows what it can manipulate — no manual pageApiDescription
  // needed from the host application.

  function collectPageDom() {
    var parts = [];

    // Headings give the LLM a structural map of the page
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
      // Short visible text hint (first 60 chars, collapsed whitespace)
      var text = (el.getAttribute('placeholder') || el.textContent || '').trim()
                   .replace(/\s+/g, ' ').substring(0, 60);
      elements.push('#' + el.id + ' <' + tag + type + '>' + (text ? ' "' + text + '"' : ''));
    });
    if (elements.length) parts.push('### Elements with IDs\n' + elements.join('\n'));

    // Labelled form inputs — especially useful for knowing editable field names
    var fields = [];
    document.querySelectorAll('input,select,textarea').forEach(function (el) {
      if (!el.id || /^vx-/.test(el.id)) return;
      var label = document.querySelector('label[for="' + el.id + '"]');
      var labelText = label ? label.textContent.trim().replace(/\s+/g, ' ') : '';
      var val = (el.type === 'password') ? '***' : String(el.value).substring(0, 40);
      var checked = (el.type === 'checkbox' || el.type === 'radio') ? ' checked=' + el.checked : '';
      fields.push('#' + el.id + ' (' + (el.type || el.tagName.toLowerCase()) + ')' +
                  (labelText ? ' label:"' + labelText + '"' : '') +
                  ' value:"' + val + '"' + checked);
    });
    if (fields.length) parts.push('### Form fields\n' + fields.join('\n'));

    return parts.join('\n\n');
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
  panel.innerHTML =
    '<div id="vx-header">' +
      '<div style="display:flex;align-items:center">' +
        '<div id="vx-header-dot"></div>' +
        '<span id="vx-header-title">' + esc(TITLE) + '</span>' +
      '</div>' +
      '<button id="vx-close" aria-label="Close">\xd7</button>' +
    '</div>' +
    '<div id="vx-messages" role="log" aria-live="polite"></div>' +
    '<div id="vx-suggestions"></div>' +
    '<div id="vx-form">' +
      '<input id="vx-input" type="text" placeholder="Ask anything…" autocomplete="off"/>' +
      '<button id="vx-send">Send</button>' +
    '</div>';

  document.body.appendChild(fab);
  document.body.appendChild(panel);

  var messagesEl    = document.getElementById('vx-messages');
  var suggestionsEl = document.getElementById('vx-suggestions');
  var inputEl       = document.getElementById('vx-input');
  var sendBtn       = document.getElementById('vx-send');

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
    var div = document.createElement('div');
    div.className = 'vx-thinking';
    div.id = 'vx-thinking';
    div.innerHTML = '<div class="vx-dot"></div><div class="vx-dot"></div><div class="vx-dot"></div>';
    messagesEl.appendChild(div);
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function hideThinking() {
    var el = document.getElementById('vx-thinking');
    if (el) el.parentNode.removeChild(el);
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
    showThinking();

    var payload = {
      message: text,
      history: history.slice(0, -1),
      context: Object.assign({}, getContext(), _pageCtx)
    };

    if (ALLOW_PAGE_SCRIPTS) {
      payload.allowPageScripts = true;
      // Auto-introspect the live DOM so the LLM knows what it can manipulate.
      // PAGE_API_DESCRIPTION (set by host app) is appended as an optional supplement.
      var domSnapshot = collectPageDom();
      payload.pageApiDescription = PAGE_API_DESCRIPTION
        ? domSnapshot + '\n\n### Host-provided notes\n' + PAGE_API_DESCRIPTION
        : domSnapshot;
    }

    var xhr = new XMLHttpRequest();
    xhr.open('POST', ENDPOINT, true);
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.timeout = 120000;

    xhr.onload = function () {
      hideThinking();
      isThinking = false;
      sendBtn.disabled = false;
      try {
        var data = JSON.parse(xhr.responseText);
        var reply = data.reply || 'No response from agent.';
        var msgEl = appendMessage('agent', reply);
        history.push({ role: 'assistant', content: reply });
        if (ALLOW_PAGE_SCRIPTS) extractAndRunScripts(reply, msgEl);
      } catch (e) {
        appendMessage('agent', 'Unexpected response from agent.');
      }
    };

    xhr.onerror = function () {
      hideThinking();
      isThinking = false;
      sendBtn.disabled = false;
      appendMessage('agent', 'Could not reach the agent. Check that the sidecar container is running.');
    };

    xhr.ontimeout = function () {
      hideThinking();
      isThinking = false;
      sendBtn.disabled = false;
      appendMessage('agent', 'The agent took too long to respond. Try a simpler query.');
    };

    xhr.send(JSON.stringify(payload));
  }

  // ── Events ────────────────────────────────────────────────────────────────────

  fab.addEventListener('click', function () {
    isOpen = !isOpen;
    panel.classList.toggle('vx-open', isOpen);
    if (isOpen) {
      renderSuggestions();
      setTimeout(function () { inputEl.focus(); }, 200);
    }
  });

  document.getElementById('vx-close').addEventListener('click', function () {
    isOpen = false;
    panel.classList.remove('vx-open');
  });

  sendBtn.addEventListener('click', function () { sendMessage(inputEl.value); });

  inputEl.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendMessage(inputEl.value);
    }
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && isOpen) {
      isOpen = false;
      panel.classList.remove('vx-open');
    }
  });

  // ── Public API ────────────────────────────────────────────────────────────────
  // VortoxAgent.setContext('key', value) — inject page-specific context into every request
  // VortoxAgent.setSuggestions([...])   — replace the suggestion chips
  cfg.setContext    = function (key, value) { _pageCtx[key] = value; };
  cfg.setSuggestions = function (suggestions) { SUGGESTIONS = suggestions; };

})();
