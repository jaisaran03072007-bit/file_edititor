const $ = id => document.getElementById(id);
let lastText = '';               // text the server last confirmed
let currentFile = '';
let currentDirty = false;
let currentLength = 0;
let queue = Promise.resolve();   // keeps requests in order

function call(endpoint, params = {}) {
  queue = queue.then(async () => {
    const res = await fetch('/api/' + endpoint, { method: 'POST', body: new URLSearchParams(params) });
    const data = await res.json();
    if (!res.ok) {
      $('error').textContent = data.error || 'Operation failed';
      await resync();
      return;
    }
    $('error').textContent = '';
    render(data);
  }).catch(e => {
    $('error').textContent = 'Network or server error: ' + String(e.message || e);
  });
  return queue;
}

function resync() {
  return fetch('/api/state', { method: 'POST' })
    .then(r => r.json())
    .then(render)
    .catch(e => {
      $('error').textContent = 'Cannot connect to Java backend. Is the server running?';
    });
}

function getFileName(path) {
  if (!path) return 'Untitled';
  const parts = path.split(/[/\\]/);
  return parts[parts.length - 1] || path;
}

function updateCursorInfo() {
  const box = $('doc');
  const pos = box.selectionStart || 0;
  const val = box.value || '';
  const lines = val.slice(0, pos).split('\n');
  const line = lines.length;
  const col = lines[lines.length - 1].length + 1;

  const cursorEl = $('cursor-stat');
  if (cursorEl) {
    cursorEl.innerHTML = `<span class="icon">📍</span> Ln <strong>${line}</strong>, Col <strong>${col}</strong> <span style="opacity:0.7">(idx ${pos})</span>`;
  }
}

function render(s) {
  const box = $('doc');
  if (box.value !== s.text) {
    box.value = s.text;
    box.setSelectionRange(s.caret, s.caret);
  }
  lastText = s.text;
  currentFile = s.file || '';
  currentDirty = s.dirty || false;
  currentLength = s.length || 0;

  // Render modern status bar pills
  const fileName = getFileName(s.file);
  const fileStatusClass = s.dirty ? 'file-unsaved' : 'file-saved';
  const fileStatusText = s.dirty ? 'Unsaved' : 'Saved';
  
  $('status').innerHTML = `
    <div class="stat-pill file-pill ${fileStatusClass}" title="${s.file || 'No file loaded'}">
      <span class="status-dot"></span>
      <span>${fileName}</span>
      <span class="file-tag">${fileStatusText}</span>
    </div>
    <div class="stat-pill">
      <span class="icon">🔤</span> <strong>${Number(s.length).toLocaleString()}</strong> chars
    </div>
    <div class="stat-pill">
      <span class="icon">🧩</span> <strong>${s.pieces}</strong> ${s.pieces === 1 ? 'piece' : 'pieces'}
    </div>
    <div class="stat-pill">
      <span class="icon">🌲</span> AVL Height: <strong>${s.height}</strong>
    </div>
    <div class="stat-pill" id="cursor-stat">
      <span class="icon">📍</span> Ln <strong>1</strong>, Col <strong>1</strong>
    </div>
  `;

  updateCursorInfo();

  if (s.file && document.activeElement !== $('path')) {
    $('path').value = s.file;
  }
  
  $('undo').disabled = !s.canUndo;
  $('redo').disabled = !s.canRedo;

  // Render tree with interactive legend
  if (!s.tree) {
    $('tree').innerHTML = `
      <div class="tree-empty">
        <span style="font-size: 28px;">📄</span>
        <span>Document is empty. Open a file or type to build the AVL tree.</span>
      </div>`;
  } else {
    $('tree').innerHTML = `
      <div class="tree-legend">
        <span class="legend-item"><span class="legend-chip orig">ORIGINAL</span> Immutable buffer</span>
        <span class="legend-item"><span class="legend-chip add">ADD</span> Append-only buffer</span>
        <span class="legend-item"><span style="color:#0369a1;font-weight:700;">L</span> / <span style="color:#6b21a8;font-weight:700;">R</span> Subtrees</span>
      </div>
      <div class="tree">
        <ul>${treeHtml(s.tree, 'root')}</ul>
      </div>`;
  }
}

function treeHtml(n, label) {
  if (!n) return '';
  if (n.hidden) {
    return `<li><span class="node-hidden">${label}: ... ${n.hidden} pieces</span></li>`;
  }
  
  const branchClass = label === 'root' ? 'branch-root' : (label === 'L' ? 'branch-L' : 'branch-R');
  const kids = [['L', n.left], ['R', n.right]].filter(k => k[1]).map(k => treeHtml(k[1], k[0])).join('');
  
  return `<li>
    <div class="node-card">
      <span class="branch-badge ${branchClass}">${label}</span>
      <span class="source-badge ${n.src}">${n.src}</span>
      <span class="node-chip range">[${n.start}..${n.start + n.len}]</span>
      <span class="node-chip">len: <b>${n.len}</b></span>
      <span class="node-chip h-val">h=${n.h}</span>
      <span class="node-chip size-val">chars=${n.size}</span>
    </div>
    ${kids ? `<ul>${kids}</ul>` : ''}
  </li>`;
}

// Typing: diff detection and atomic replacement
const isHigh = c => c >= 0xD800 && c <= 0xDBFF;
const isLow = c => c >= 0xDC00 && c <= 0xDFFF;

$('doc').addEventListener('input', () => {
  const oldT = lastText, newT = $('doc').value;
  let pre = 0;
  while (pre < oldT.length && pre < newT.length && oldT[pre] === newT[pre]) pre++;
  let suf = 0;
  while (suf < oldT.length - pre && suf < newT.length - pre &&
         oldT[oldT.length - 1 - suf] === newT[newT.length - 1 - suf]) suf++;
  if (pre > 0 && isHigh(oldT.charCodeAt(pre - 1))) pre--; // never split an emoji
  if (suf > 0 && isLow(oldT.charCodeAt(oldT.length - suf))) suf--;
  lastText = newT;
  call('replace', { pos: pre, len: oldT.length - pre - suf, text: newT.slice(pre, newT.length - suf) });
  updateCursorInfo();
});

// Update cursor line/col on interaction
['click', 'keyup', 'select'].forEach(evt => {
  $('doc').addEventListener(evt, updateCursorInfo);
});

// Keyboard shortcuts
$('doc').addEventListener('keydown', e => {
  if (!(e.ctrlKey || e.metaKey)) return;
  const k = e.key.toLowerCase();
  if (k === 'z' && !e.shiftKey) { e.preventDefault(); call('undo'); }
  else if (k === 'y' || (k === 'z' && e.shiftKey)) { e.preventDefault(); call('redo'); }
  else if (k === 's') { e.preventDefault(); call('save'); }
});

// Button event listeners
$('undo').onclick = () => call('undo');
$('redo').onclick = () => call('redo');
$('save').onclick = () => call('save');
$('open').onclick = () => {
  const p = $('path').value.trim();
  if (!p) { $('error').textContent = 'Please enter a valid file path to open.'; return; }
  call('openfile', { path: p });
};
$('saveas').onclick = () => {
  const p = $('path').value.trim();
  if (!p) { $('error').textContent = 'Please specify a target file path in the input box.'; return; }
  call('saveas', { path: p });
};

$('insert').onclick = () => {
  const pos = $('ipos').value;
  const text = $('itext').value;
  if (pos === '') { $('error').textContent = 'Please specify an insert position.'; return; }
  call('insert', { pos, text });
};

$('insertCaret').onclick = () => {
  const pos = $('doc').selectionStart;
  const text = $('itext').value;
  call('insert', { pos, text });
};

$('delete').onclick = () => {
  const pos = $('dpos').value;
  const len = $('dlen').value;
  if (pos === '' || len === '') { $('error').textContent = 'Please specify both start position and length to delete.'; return; }
  call('delete', { pos, len });
};

$('deleteSel').onclick = () => {
  const d = $('doc');
  const start = d.selectionStart;
  const end = d.selectionEnd;
  if (start === end) {
    $('error').textContent = 'No text currently selected in the document to delete.';
    return;
  }
  call('delete', { pos: start, len: end - start });
};

// Helpful tooltips for shortcuts
$('undo').title = 'Undo (Ctrl+Z / Cmd+Z)';
$('redo').title = 'Redo (Ctrl+Y / Cmd+Shift+Z)';
$('save').title = 'Save (Ctrl+S / Cmd+S)';

// Initial synchronization with backend
resync();
