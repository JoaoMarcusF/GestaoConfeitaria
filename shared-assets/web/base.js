/* Base: comunicação com o app (Android ou computador), utilidades, rotas, modais. */
'use strict';

const GC = {
  plataforma: window.GC_PLATAFORMA || (window.GCNativo ? 'android' : 'web'),
  _seq: 0,
  _pendentes: {},
  chamar(metodo, args = {}) {
    if (window.GCNativo) {
      return new Promise((ok, erro) => {
        const id = ++GC._seq;
        GC._pendentes[id] = { ok, erro };
        window.GCNativo.chamar(id, metodo, JSON.stringify(args));
      });
    }
    return fetch('/api/' + metodo, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-GC-Token': window.GC_TOKEN || '' },
      body: JSON.stringify(args),
    }).then(r => r.json()).then(j => { if ('erro' in j) throw new Error(j.erro); return j.ok; });
  },
};
// Resposta da ponte do Android
window.gcResposta = (id, texto) => {
  const p = GC._pendentes[id]; if (!p) return;
  delete GC._pendentes[id];
  let j; try { j = JSON.parse(texto); } catch (e) { p.erro(e); return; }
  if (j && 'erro' in j) p.erro(new Error(j.erro)); else p.ok(j ? j.ok : null);
};
const ehAndroid = GC.plataforma === 'android';
const ehDesktop = GC.plataforma === 'desktop';

/* ---------------- estado ---------------- */
const S = {
  dados: { notas: [], vendas: [], receitas: [], insumos: [], config: {} },
  versao: -1,
  dispositivo: {},
  filtro: {},          // filtro do painel
  editando: false,     // evita recarregar a tela no meio de um formulário
};

/* ---------------- utilidades ---------------- */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtBRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const brl = v => (v == null || isNaN(v)) ? '—' : fmtBRL.format(v);
const brl0 = v => (v == null || isNaN(v)) ? '—' : 'R$ ' + Math.round(v).toLocaleString('pt-BR');
const pct = (v, c = 1) => (v == null || isNaN(v)) ? '—' : (v * 100).toLocaleString('pt-BR', { minimumFractionDigits: c, maximumFractionDigits: c }) + '%';
const numBR = (v, c = 3) => (v == null || isNaN(v)) ? '' : Number(v).toLocaleString('pt-BR', { maximumFractionDigits: c });
const MESES = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'];
const MESES_LONGOS = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'];
const rotMes = ym => ym && ym.length >= 7 ? MESES[+ym.slice(5, 7) - 1] + '/' + ym.slice(2, 4) : ym || '';
const mesLongo = ym => ym && ym.length >= 7 ? MESES_LONGOS[+ym.slice(5, 7) - 1] + ' de ' + ym.slice(0, 4) : 'Sem data';
const dataBR = iso => iso && iso.length >= 10 ? `${iso.slice(8, 10)}/${iso.slice(5, 7)}/${iso.slice(0, 4)}` : '';
const hoje = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);
const mesAtual = () => hoje().slice(0, 7);
const somaMeses = (ym, n) => { const d = new Date(+ym.slice(0, 4), +ym.slice(5, 7) - 1 + n, 1); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0'); };
const lerNum = s => { if (typeof s === 'number') return s; s = String(s ?? '').trim(); if (!s) return null; if (s.includes(',')) s = s.replace(/\./g, '').replace(',', '.'); const n = parseFloat(s); return isNaN(n) ? null : n; };
const norm = s => String(s ?? '').normalize('NFD').replace(/\p{M}+/gu, '').toUpperCase();
const debounce = (f, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => f(...a), ms); }; };

const ICONES = {
  qr: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 14h2M14 18h2v2M18 18h2v2"/></svg>',
  foto: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="6" width="18" height="14" rx="3"/><circle cx="12" cy="13" r="3.5"/><path d="M8 6l1.5-2h5L16 6"/></svg>',
  link: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M10 14a4 4 0 0 0 5.6 0l3-3a4 4 0 0 0-5.6-5.6l-1 1M14 10a4 4 0 0 0-5.6 0l-3 3a4 4 0 0 0 5.6 5.6l1-1"/></svg>',
  chave: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 7h16M4 12h16M4 17h16M8 4v16M16 4v16"/></svg>',
  xml: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H6v18h12V7z"/><path d="M14 3v4h4M9 13l-2 2 2 2M15 13l2 2-2 2"/></svg>',
  lapis: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M4 20h4L19 9l-4-4L4 16z"/></svg>',
  planilha: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="3" width="16" height="18" rx="2"/><path d="M4 9h16M4 15h16M10 3v18"/></svg>',
  nota: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M6 3h12v18l-3-2-3 2-3-2-3 2z"/></svg>',
  baixar: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg>',
  mais: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>',
  ok: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>',
  relogio: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="13" r="8"/><path d="M12 9v4l3 2M9 3h6"/></svg>',
};

/* ---------------- avisos e modais ---------------- */
function toast(msg, erro = false) {
  $$('.toast').forEach(x => x.remove());   // um aviso por vez
  const t = document.createElement('div');
  t.className = 'toast' + (erro ? ' erro' : '');
  t.textContent = msg;
  t.setAttribute('role', 'status');
  document.body.appendChild(t);
  setTimeout(() => t.remove(), erro ? 6000 : 3200);
}
const falha = e => { const m = e && e.message ? e.message : String(e); if (m !== 'cancelado') toast(m, true); };

/** Abre um modal. `conteudo` é HTML; `aoAbrir(el, fechar)` liga os eventos. Retorna uma função que fecha. */
function modal(conteudo, aoAbrir) {
  const fundo = document.createElement('div');
  fundo.className = 'fundo-modal';
  fundo.innerHTML = `<div class="modal" role="dialog" aria-modal="true">${conteudo}</div>`;
  const fechar = () => { fundo.remove(); document.removeEventListener('keydown', esc_); };
  const esc_ = e => { if (e.key === 'Escape') fechar(); };
  fundo.addEventListener('click', e => { if (e.target === fundo) fechar(); });
  document.addEventListener('keydown', esc_);
  document.body.appendChild(fundo);
  $$('[data-fechar]', fundo).forEach(b => b.addEventListener('click', fechar));
  aoAbrir && aoAbrir(fundo.firstElementChild, fechar);
  const primeiro = $('input, select, textarea', fundo);
  if (primeiro && window.innerWidth > 720) primeiro.focus();
  return fechar;
}

function confirmar(titulo, texto, rotuloOk = 'Confirmar', perigo = false) {
  return new Promise(res => {
    modal(`<h2>${esc(titulo)}</h2><p class="sec">${esc(texto)}</p>
      <div class="rodape"><button class="btn" data-fechar>Cancelar</button><button class="btn ${perigo ? 'perigo' : 'pri'}" id="mOk">${esc(rotuloOk)}</button></div>`,
      (el, fechar) => { $('#mOk', el).onclick = () => { fechar(); res(true); }; el.parentElement.addEventListener('click', e => { if (e.target === el.parentElement) res(false); }); $$('[data-fechar]', el).forEach(b => b.addEventListener('click', () => res(false))); });
  });
}

/** Lê um arquivo escolhido pelo usuário. modo: 'texto' | 'base64' */
function escolherArquivo(aceita, modo) {
  return new Promise((ok, erro) => {
    const i = document.createElement('input');
    i.type = 'file'; i.accept = aceita; i.style.display = 'none';
    document.body.appendChild(i);
    i.onchange = () => {
      const f = i.files[0]; i.remove(); if (!f) return;
      const r = new FileReader();
      r.onerror = () => erro(new Error('Não foi possível ler o arquivo.'));
      r.onload = () => ok({ nome: f.name, conteudo: modo === 'texto' ? r.result : String(r.result).split(',')[1] });
      if (modo === 'texto') r.readAsText(f); else r.readAsDataURL(f);
    };
    i.click();
  });
}

async function baixarExcel(filtro = {}) {
  try {
    if (ehAndroid) { await GC.chamar('nativo.salvarExcel', { filtro }); return; }
    if (ehDesktop) { location.href = '/baixar/excel?t=' + encodeURIComponent(window.GC_TOKEN) + '&filtro=' + encodeURIComponent(JSON.stringify(filtro)); toast('Gerando a planilha…'); return; }
    const r = await GC.chamar('exportarExcel', filtro);
    const a = document.createElement('a'); a.href = 'data:application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;base64,' + r.base64; a.download = r.nome; a.click();
  } catch (e) { falha(e); }
}

/* ---------------- dados ---------------- */
async function recarregar() {
  const d = await GC.chamar('dados');
  S.dados = d; S.versao = d.versao;
  const nome = (d.config && d.config.nomeNegocio) || '';
  $('#marca').innerHTML = nome ? `${esc(nome)}<small>Gestão da confeitaria</small>` : 'Gestão Confeitaria';
}
const porId = (col, id) => (S.dados[col] || []).find(x => x.id === id);
async function salvar(col, rec) { const r = await GC.chamar('salvar', { col, rec }); await recarregar(); return r; }
async function excluir(col, id) { await GC.chamar('excluir', { col, id }); await recarregar(); }

/* ---------------- rotas ---------------- */
const TELAS = {};   // nome → função(params) que devolve HTML ou Promise<HTML>; pode definir .depois(el)
const ACOES = {};   // data-acao → função(el, evento)

function rotaAtual() {
  const h = location.hash.replace(/^#\/?/, '') || 'resumo';
  const [caminho, consulta] = h.split('?');
  const partes = caminho.split('/');
  const params = Object.fromEntries(new URLSearchParams(consulta || ''));
  return { tela: partes[0], id: partes[1], sub: partes[2], params };
}
function ir(h) { if (location.hash === '#/' + h) render(); else location.hash = '#/' + h; }

async function render() {
  const r = rotaAtual();
  const tela = TELAS[r.tela] || TELAS.resumo;
  const nav = { nota: 'notas', adicionar: 'notas', receita: 'receitas', insumos: 'receitas', insumo: 'receitas', indiretos: 'receitas', venda: 'vendas', dispositivos: 'mais' }[r.tela] || r.tela;
  $$('#nav a').forEach(a => a.classList.toggle('ativo', a.dataset.rota === nav));
  S.editando = !!tela.editavel;
  const app = $('#app');
  try {
    const html = await tela(r);
    if (html != null) { app.innerHTML = html; tela.depois && tela.depois(app, r); }
  } catch (e) {
    app.innerHTML = `<div class="vazio"><p>Não foi possível abrir esta tela.</p><p class="sec">${esc(e.message || e)}</p></div>`;
  }
}
window.addEventListener('hashchange', () => { render(); window.scrollTo(0, 0); });

document.addEventListener('click', e => {
  const el = e.target.closest('[data-acao]');
  if (!el) return;
  const f = ACOES[el.dataset.acao];
  if (f) { e.preventDefault(); f(el, e); }
});
ACOES.ir = el => ir(el.dataset.para);
ACOES.voltar = () => history.length > 1 ? history.back() : ir('resumo');
ACOES.irDispositivos = () => ir('dispositivos');

/* ---------------- início ---------------- */
async function iniciar() {
  try {
    const i = await GC.chamar('inicio');
    S.dispositivo = i.dispositivo;
    await recarregar();
    render();
    atualizarPill();
    // Acompanha mudanças vindas da sincronização (e pedidos de pareamento, no computador)
    setInterval(async () => {
      try {
        const v = await GC.chamar('versao');
        if (v.pareamento) mostrarPareamento(v.pareamento); else if (window.__fecharPar) { window.__fecharPar(); window.__fecharPar = null; window.__parSid = null; toast('Aparelho pareado. Os dados vão sincronizar sozinhos.'); render(); }
        if (v.versao !== S.versao) { await recarregar(); if (!S.editando && !document.querySelector('.fundo-modal')) render(); atualizarPill(); }
      } catch (e) { /* app fechando */ }
    }, ehDesktop ? 2500 : 5000);
  } catch (e) {
    $('#app').innerHTML = `<div class="vazio"><p>Não foi possível iniciar.</p><p class="sec">${esc(e.message)}</p></div>`;
  }
}

/** No computador: mostra o código que o celular precisa digitar. */
function mostrarPareamento(p) {
  if (window.__parSid === p.sid) return;
  window.__fecharPar && window.__fecharPar();
  window.__parSid = p.sid;
  window.__fecharPar = modal(`<h2>Parear com ${esc(p.dispositivo)}</h2>
    <p class="sec">Digite este código no celular para confirmar que é o seu aparelho.</p>
    <div class="codigo-grande">${p.codigo.slice(0, 3)} ${p.codigo.slice(3)}</div>
    <p class="sec peq">Se você não pediu este pareamento, cancele.</p>
    <div class="rodape"><button class="btn" id="parCancelar">Cancelar</button></div>`, (el, fechar) => {
    $('#parCancelar', el).onclick = () => { GC.chamar('cancelarPareamento', { sid: p.sid }); fechar(); window.__fecharPar = null; };
  });
}

async function atualizarPill() {
  try {
    const d = await GC.chamar('dispositivos');
    const pill = $('#syncPill');
    const pares = d.pares || [];
    if (!pares.length) { pill.hidden = true; return; }
    const ult = Math.max(...pares.map(p => p.ultimoSync || 0));
    pill.hidden = false;
    pill.className = 'sync-pill ' + (ult && Date.now() - ult < 10 * 60000 ? 'ok' : '');
    pill.textContent = ult ? 'Sincronizado ' + tempoRelativo(ult) : 'Aparelho pareado';
  } catch (e) { }
}
function tempoRelativo(ms) {
  const s = (Date.now() - ms) / 1000;
  if (s < 60) return 'agora'; if (s < 3600) return `há ${Math.round(s / 60)} min`;
  if (s < 86400) return `há ${Math.round(s / 3600)} h`; return 'em ' + new Date(ms).toLocaleDateString('pt-BR');
}

document.addEventListener('DOMContentLoaded', iniciar);
