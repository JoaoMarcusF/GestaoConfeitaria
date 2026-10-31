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

  /**
   * Pizza. itens: [{nome, valor}]. Até 6 fatias; o restante vira "Demais" (cinza).
   * Cores na ordem fixa da paleta validada para daltonismo; a legenda mostra nome, valor e % (não depende só da cor).
   */
  CORES_PIZZA: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300'],
  pizza(itens, { acao = null, tamanho = 170, compacto = false } = {}) {
    let lista = (itens || []).filter(i => i.valor > 0).map(i => ({ ...i }));
    if (!lista.length) return '<p class="sec">Sem dados no período.</p>';
    if (lista.length > 6) {
      const resto = lista.slice(6);
      lista = lista.slice(0, 6).concat([{ nome: 'Demais', valor: resto.reduce((a, x) => a + x.valor, 0), demais: true, qtd: resto.length }]);
    }
    const total = lista.reduce((a, x) => a + x.valor, 0);
    const R = 50, C = 52;
    let ang = -Math.PI / 2, fatias = '';
    lista.forEach((it, k) => {
      const cor = it.demais ? '#9aa5ab' : Graficos.CORES_PIZZA[k];
      const frac = it.valor / total;
      it.cor = cor; it.frac = frac;
      const tt = `<title>${esc(it.nome)}: ${brl(it.valor)} (${pct(frac)})</title>`;
      const attrs = acao && !it.demais ? ` data-acao="${acao}" data-chave="${esc(it.chave ?? it.nome)}" data-nome="${esc(it.nome)}" style="cursor:pointer"` : '';
      if (frac >= 0.9999) { fatias += `<circle cx="${C}" cy="${C}" r="${R}" fill="${cor}"${attrs}>${tt}</circle>`; return; }
      const a2 = ang + frac * 2 * Math.PI;
      const x1 = C + R * Math.cos(ang), y1 = C + R * Math.sin(ang), x2 = C + R * Math.cos(a2), y2 = C + R * Math.sin(a2);
      fatias += `<path class="fatia" d="M${C},${C} L${x1.toFixed(2)},${y1.toFixed(2)} A${R},${R} 0 ${frac > .5 ? 1 : 0} 1 ${x2.toFixed(2)},${y2.toFixed(2)} Z" fill="${cor}" stroke="#fff" stroke-width="2" stroke-linejoin="round"${attrs}>${tt}</path>`;
      ang = a2;
    });
    const legenda = lista.map(it => `<li${acao && !it.demais ? ` data-acao="${acao}" data-chave="${esc(it.chave ?? it.nome)}" data-nome="${esc(it.nome)}"` : ''}>
        <i style="background:${it.cor}"></i><span class="nome" title="${esc(it.nome)}">${esc(it.nome)}${it.demais ? ` <span class="sec">(${it.qtd})</span>` : ''}</span>
        ${compacto ? '' : `<span class="val">${brl(it.valor)}</span>`}<span class="pc">${pct(it.frac)}</span></li>`).join('');
    return `<div class="pizza${compacto ? ' compacta' : ''}"><svg viewBox="0 0 104 104" width="${tamanho}" height="${tamanho}" role="img" aria-label="Gráfico de pizza">${fatias}</svg>
      <ul class="pizza-leg">${legenda}</ul></div>`;
  },

  /**
   * Gráfico de setas: em cada mês, uma seta vai do valor do mês anterior até o valor do mês.
   * Seta para cima = cresceu; para baixo = caiu. A cor diz se a mudança foi boa (verde) ou ruim (vermelho):
   * para gastos, subir é ruim; para vendas e lucro, subir é bom.
   */
  setas(serie, campo, { subirEhBom = true, selecionado = '' } = {}) {
    const vals = serie.map(s => s[campo] || 0);
    const n = serie.length;
    if (n < 2) return '<p class="sec">Escolha um período com pelo menos dois meses para ver o crescimento.</p>';
    const W = Math.max(320, n * 48 + 54), H = 240, base = 200, topo = 22, esq = 46;
    const minV = Math.min(0, ...vals), maxV = Math.max(1, ...vals);
    const passo = Graficos._passo((maxV - minV) || 1);
    const piso = Math.floor(minV / passo) * passo, teto = Math.ceil(maxV / passo) * passo;
    const y = v => base - ((v - piso) / (teto - piso || 1)) * (base - topo);
    const larg = (W - esq - 8) / n, cx = i => esq + larg * i + larg / 2;
    const BOM = '#0F766E', RUIM = '#B4372F', NEUTRO = '#8A979E';
    // Rótulos só onde importa: maior alta, maior queda e o último mês
    const vars = vals.map((v, i) => i === 0 || vals[i - 1] === 0 ? null : (v - vals[i - 1]) / Math.abs(vals[i - 1]));
    const comVar = vars.map((v, i) => [v, i]).filter(([v]) => v != null);
    const iAlta = comVar.length ? comVar.reduce((a, b) => b[0] > a[0] ? b : a)[1] : -1;
    const iQueda = comVar.length ? comVar.reduce((a, b) => b[0] < a[0] ? b : a)[1] : -1;
    const rotular = new Set([iAlta, iQueda, n - 1]);
    let g = '';
    for (let v = piso; v <= teto + 1e-9; v += passo) {
      g += `<line class="grade-l" x1="${esq}" x2="${W - 4}" y1="${y(v)}" y2="${y(v)}"${Math.abs(v) < 1e-9 && piso < 0 ? ' style="stroke:#9aa5ab"' : ''}/><text x="${esq - 6}" y="${y(v) + 4}" text-anchor="end">${v < 0 ? '−' : ''}${Graficos._curto(Math.abs(v))}</text>`;
    }
    // linha de referência suave ligando os valores
    g += `<polyline points="${vals.map((v, i) => `${cx(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')}" fill="none" stroke="#d3dcd9" stroke-width="2" stroke-linejoin="round"/>`;
    serie.forEach((s, i) => {
      const x = cx(i), v = vals[i], ant = i ? vals[i - 1] : null;
      const apagada = selecionado && selecionado !== s.mes ? ' apagada' : '';
      const variacao = vars[i];
      const desc = i === 0 ? `${s.rotulo}: ${brl(v)}` : `${s.rotulo}: ${brl(v)} (${variacao == null ? 'sem base para comparar' : (variacao >= 0 ? '▲ +' : '▼ −') + pct(Math.abs(variacao))} vs ${serie[i - 1].rotulo})`;
      g += `<g class="barra${apagada}" data-acao="filtrarMes" data-mes="${s.mes}" role="button" aria-label="${esc(desc)}"><title>${esc(desc)}</title>`;
      g += `<rect x="${x - larg / 2}" y="${topo - 14}" width="${larg}" height="${base - topo + 36}" fill="transparent"/>`;
      if (i > 0) {
        const y0 = y(ant), y1 = y(v), sobe = v > ant, igual = Math.abs(v - ant) < 0.005;
        const cor = igual ? NEUTRO : (sobe === subirEhBom ? BOM : RUIM);
        if (!igual && Math.abs(y1 - y0) >= 9) {
          const ponta = sobe ? y1 + 7 : y1 - 7;
          g += `<line x1="${x}" y1="${y0}" x2="${x}" y2="${ponta}" stroke="${cor}" stroke-width="3" stroke-linecap="round"/>`;
          g += `<path d="M${x - 6},${ponta} L${x + 6},${ponta} L${x},${y1} Z" fill="${cor}"/>`;
        } else {
          g += `<circle cx="${x}" cy="${y1}" r="4.5" fill="${cor}"/>`;
        }
        g += `<circle cx="${x}" cy="${y0}" r="3" fill="#fff" stroke="${cor}" stroke-width="1.5"/>`;
        if (rotular.has(i) && variacao != null) {
          const ty = sobe ? y1 - 6 : y1 + 15;
          g += `<text x="${x}" y="${Math.max(12, Math.min(base - 2, ty))}" text-anchor="middle" style="font-weight:700;fill:#1E2A33">${variacao >= 0 ? '+' : '−'}${pct(Math.abs(variacao), 0)}</text>`;
        }
      } else {
        g += `<circle cx="${x}" cy="${y(v)}" r="4.5" fill="${NEUTRO}"/>`;
      }
      g += `<text x="${x}" y="${base + 20}" text-anchor="middle"${selecionado === s.mes ? ' style="font-weight:700;fill:#1E2A33"' : ''}>${esc(s.rotulo)}</text></g>`;
    });
    return `<div class="rolar"><svg class="grafico-mes" viewBox="0 0 ${W} ${H}" style="min-width:${Math.min(W, 600)}px">${g}</svg></div>`;
  },
};
