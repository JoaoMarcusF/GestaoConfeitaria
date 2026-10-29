/* Gráficos leves em SVG/HTML (sem bibliotecas). */
'use strict';

const Graficos = {
  /** Barras por mês (gastos e vendas). `selecionado` = 'YYYY-MM' destacado. */
  meses(serie, selecionado) {
    const n = serie.length || 1;
    const temVendas = serie.some(s => s.vendas > 0);
    const W = Math.max(320, n * 48 + 54), H = 230, base = 196, topo = 14, esq = 46;
    const max = Math.max(1, ...serie.map(s => Math.max(s.gastos || 0, s.vendas || 0)));
    const passo = Graficos._passo(max);
    const teto = Math.ceil(max / passo) * passo;
    const y = v => base - (v / teto) * (base - topo);
    const larg = (W - esq - 8) / n;
    const bw = Math.min(30, larg * (temVendas ? .36 : .58));
    let g = '';
    for (let v = 0; v <= teto + 1e-9; v += passo) {
      g += `<line class="grade-l" x1="${esq}" x2="${W - 4}" y1="${y(v)}" y2="${y(v)}"/><text x="${esq - 6}" y="${y(v) + 4}" text-anchor="end">${Graficos._curto(v)}</text>`;
    }
    serie.forEach((s, i) => {
      const cx = esq + larg * i + larg / 2;
      const apagada = selecionado && selecionado !== s.mes ? ' apagada' : '';
      const x1 = temVendas ? cx - bw - 1 : cx - bw / 2;
      const hG = base - y(s.gastos || 0);
      g += `<g class="barra${apagada}" data-acao="filtrarMes" data-mes="${s.mes}" role="button" aria-label="${esc(s.rotulo)}: gastos ${brl(s.gastos)}${temVendas ? ', vendas ' + brl(s.vendas) : ''}">`;
      g += `<rect x="${cx - larg / 2}" y="${topo}" width="${larg}" height="${base - topo + 22}" fill="transparent"/>`;
      g += `<rect x="${x1}" y="${base - hG}" width="${bw}" height="${Math.max(hG, s.gastos > 0 ? 1.5 : 0)}" rx="4" fill="#0F766E"><title>${esc(s.rotulo)} — gastos ${brl(s.gastos)}</title></rect>`;
      if (temVendas) {
        const hV = base - y(s.vendas || 0);
        g += `<rect x="${cx + 1}" y="${base - hV}" width="${bw}" height="${Math.max(hV, s.vendas > 0 ? 1.5 : 0)}" rx="4" fill="#B83D6E"><title>${esc(s.rotulo)} — vendas ${brl(s.vendas)}</title></rect>`;
      }
      g += `<text x="${cx}" y="${base + 17}" text-anchor="middle"${selecionado === s.mes ? ' style="font-weight:700;fill:#1E2A33"' : ''}>${esc(s.rotulo)}</text></g>`;
    });
    return `<div class="rolar"><svg class="grafico-mes" viewBox="0 0 ${W} ${H}" style="min-width:${Math.min(W, 600)}px">${g}</svg></div>`;
  },

  /** Barras horizontais. itens: [{nome, valor, pct?}], acao: data-acao ao clicar (opcional). */
  barras(itens, { cor = '', acao = null, max = 6, mostrarPct = true } = {}) {
    if (!itens || !itens.length) return '<p class="sec">Sem dados no período.</p>';
    const lista = itens.slice(0, max);
    const topo = Math.max(...lista.map(i => i.valor || 0), 1);
    return `<ul class="hbar ${cor}">` + lista.map(i => `
      <li${acao ? ` data-acao="${acao}" data-chave="${esc(i.chave ?? i.nome)}" data-nome="${esc(i.nome)}"` : ''}>
        <span class="nome" title="${esc(i.nome)}">${esc(i.nome)}</span>
        <span class="val">${brl(i.valor)}${mostrarPct && i.pct != null ? ` <span class="sec">${pct(i.pct)}</span>` : ''}</span>
        <span class="trilho"><b style="width:${Math.max(1.5, (i.valor / topo) * 100).toFixed(1)}%"></b></span>
      </li>`).join('') + '</ul>';
  },

  _passo(max) {
    const bruto = max / 4, ex = Math.pow(10, Math.floor(Math.log10(bruto))), f = bruto / ex;
    return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * ex;
  },
  _curto(v) {
    if (v >= 1000) return (v / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' mil';
    return Math.round(v).toLocaleString('pt-BR');
  },
};
