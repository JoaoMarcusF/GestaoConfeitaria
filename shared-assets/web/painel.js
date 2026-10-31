/* Resumo: painel dinâmico com indicadores financeiros. */
'use strict';

S.filtro = { preset: '12m', categoria: '', mes: '' };
S.metricaSeta = 'gastos';

/** Barras ou pizza em cada bloco (lembrado neste aparelho). */
const VIS = {
  ler(b) { try { return localStorage.getItem('gc.vis.' + b) || 'barras'; } catch (e) { return VIS['_' + b] || 'barras'; } },
  gravar(b, v) { VIS['_' + b] = v; try { localStorage.setItem('gc.vis.' + b, v); } catch (e) { } },
};
function blocoVis(id, titulo, dica, itens, opBarras, opPizza = {}) {
  const v = VIS.ler(id);
  return `<section class="bloco"><header><h2>${titulo}</h2><span style="display:flex;gap:8px;align-items:center">${dica ? `<span class="sec peq">${dica}</span>` : ''}
      <span class="vis-troca" role="group" aria-label="Tipo de gráfico"><button class="${v === 'barras' ? 'on' : ''}" data-acao="trocarVis" data-b="${id}" data-v="barras">Barras</button><button class="${v === 'pizza' ? 'on' : ''}" data-acao="trocarVis" data-b="${id}" data-v="pizza">Pizza</button></span></span></header>
    ${v === 'pizza' ? Graficos.pizza(itens, { acao: opBarras.acao, ...opPizza }) : Graficos.barras(itens, opBarras)}</section>`;
}
ACOES.trocarVis = el => { VIS.gravar(el.dataset.b, el.dataset.v); const y = window.scrollY; render().then(() => window.scrollTo(0, y)); };

/** "▲ 12,3% vs período anterior", com cor de bom/ruim conforme o que é desejável. */
function variacao(atual, anterior, subirEhBom, quando = 'vs período anterior') {
  if (!anterior) return { t: 'sem período anterior para comparar', c: '' };
  const v = (atual - anterior) / Math.abs(anterior);
  if (Math.abs(v) < 0.0005) return { t: `= estável ${quando}`, c: '' };
  return { t: `${v > 0 ? '▲' : '▼'} ${pct(Math.abs(v))} ${quando}`, c: (v > 0) === subirEhBom ? 'bom' : 'ruim' };
}

const METRICAS = { gastos: ['Gastos', false], vendas: ['Vendas', true], lucro: ['Lucro', true] };
function blocoSetas(serie) {
  const temVendas = serie.some(s => s.vendas > 0);
  const m = !temVendas ? 'gastos' : S.metricaSeta;
  const [nome, subirEhBom] = METRICAS[m];
  const vals = serie.map(s => s[m] || 0);
  const vars = vals.map((v, i) => i && vals[i - 1] ? { i, v: (v - vals[i - 1]) / Math.abs(vals[i - 1]) } : null).filter(Boolean);
  const pilula = (rot, x, bomSe) => x ? `<span class="pilula ${(x.v > 0) === bomSe ? 'bom' : 'ruim'}">${rot}: <b>${x.v > 0 ? '▲' : '▼'} ${pct(Math.abs(x.v))}</b> em ${serie[x.i].rotulo}</span>` : '';
  const alta = vars.length ? vars.reduce((a, b) => b.v > a.v ? b : a) : null;
  const queda = vars.length ? vars.reduce((a, b) => b.v < a.v ? b : a) : null;
  let tendencia = '';
  if (vals.length >= 6) {
    const ult = vals.slice(-3).reduce((a, b) => a + b, 0), ant = vals.slice(-6, -3).reduce((a, b) => a + b, 0);
    if (ant) { const v = (ult - ant) / Math.abs(ant); tendencia = `<span class="pilula ${(v > 0) === subirEhBom ? 'bom' : 'ruim'}">Últimos 3 meses vs 3 anteriores: <b>${v > 0 ? '▲' : '▼'} ${pct(Math.abs(v))}</b></span>`; }
  }
  return `<section class="bloco" style="margin-bottom:14px"><header><h2>Crescimento e queda mês a mês</h2>
      <div class="segmento">${Object.entries(METRICAS).map(([k, [n]]) => `<button class="${k === m ? 'on' : ''}" data-acao="metricaSeta" data-m="${k}" ${k !== 'gastos' && !temVendas ? 'disabled title="Registre vendas para ver"' : ''}>${n}</button>`).join('')}</div></header>
    <p class="sec peq" style="margin-bottom:6px">Cada seta vai do valor do mês anterior ao do mês. <span style="color:var(--menta);font-weight:600">Verde</span> é bom e <span style="color:var(--perigo);font-weight:600">vermelho</span> é ruim${m === 'gastos' ? ': gasto subindo é vermelho' : ''}.</p>
    ${Graficos.setas(serie, m, { subirEhBom, selecionado: S.filtro.mes })}
    <div class="resumo-setas">${pilula('Maior alta', alta && alta.v > 0 ? alta : null, subirEhBom)}${pilula('Maior queda', queda && queda.v < 0 ? queda : null, subirEhBom)}${tendencia}</div>
  </section>`;
}
ACOES.metricaSeta = el => { S.metricaSeta = el.dataset.m; const y = window.scrollY; render().then(() => window.scrollTo(0, y)); };

function ultimoMesComDados() {
  let m = '';
  S.dados.notas.forEach(n => { const x = (n.emissao || '').slice(0, 7); if (x > m) m = x; });
  S.dados.vendas.forEach(v => { const x = (v.data || '').slice(0, 7); if (x > m) m = x; });
  return m || mesAtual();
}
function primeiroMesComDados() {
  let m = '9999-99';
  S.dados.notas.forEach(n => { const x = (n.emissao || '').slice(0, 7); if (x && x < m) m = x; });
  S.dados.vendas.forEach(v => { const x = (v.data || '').slice(0, 7); if (x && x < m) m = x; });
  return m === '9999-99' ? mesAtual() : m;
}
function intervaloDoFiltro() {
  const f = S.filtro, ult = ultimoMesComDados();
  switch (f.preset) {
    case '3m': return { de: somaMeses(ult, -2), ate: ult };
    case '6m': return { de: somaMeses(ult, -5), ate: ult };
    case 'ano': return { de: ult.slice(0, 4) + '-01', ate: ult };
    case 'tudo': return { de: primeiroMesComDados(), ate: ult };
    case 'pers': return { de: f.de || somaMeses(ult, -11), ate: f.ate || ult };
    default: return { de: somaMeses(ult, -11), ate: ult };
  }
}

const PRESETS = [['3m', '3 meses'], ['6m', '6 meses'], ['12m', '12 meses'], ['ano', 'Este ano'], ['tudo', 'Tudo'], ['pers', 'Escolher período']];

TELAS.resumo = async () => {
  if (!S.dados.notas.length && !S.dados.vendas.length) {
    return `<div class="cabeca"><h1>Resumo</h1></div>
      <div class="bloco vazio">
        <h2>Comece trazendo suas notas</h2>
        <p class="sec" style="max-width:46ch;margin:8px auto 0">Escaneie o QR code de um cupom, lance uma compra à mão ou importe a planilha de controle que você já usa. O resumo aparece aqui.</p>
        <div class="chips" style="justify-content:center;margin-top:16px">
          <button class="btn pri" data-acao="ir" data-para="adicionar">${ICONES.mais} Adicionar nota</button>
          <button class="btn" data-acao="importarPlanilha">${ICONES.planilha} Importar planilha</button>
        </div>
      </div>`;
  }
  const iv = intervaloDoFiltro();
  const base = { de: iv.de, ate: iv.ate, categoria: S.filtro.categoria };
  const geral = await GC.chamar('painel', base);
  const p = S.filtro.mes ? await GC.chamar('painel', { ...base, de: S.filtro.mes, ate: S.filtro.mes }) : geral;
  const k = p.kpis;
  const cats = geral.categoriasDisponiveis || [];
  const quando = S.filtro.mes ? 'vs mês anterior' : 'vs período anterior';
  const cg = variacao(k.gastoTotal, k.gastoAnterior, false, quando);
  const cf = variacao(k.faturamento, k.faturamentoAnterior, true, quando);
  const cr = k.temAnterior && k.faturamentoAnterior > 0 ? variacao(k.resultado, k.resultadoAnterior, true, quando) : null;
  const tituloPeriodo = S.filtro.mes ? mesLongo(S.filtro.mes) : p.periodo;

  return `
  <div class="cabeca">
    <div><h1>Resumo</h1><p class="sec">${esc(tituloPeriodo)}, ${p.linhas} itens analisados</p></div>
    <div class="acoes">
      <button class="btn fr" data-acao="novaVenda">${ICONES.mais} Venda</button>
      <button class="btn pri" data-acao="ir" data-para="adicionar">${ICONES.mais} Nota</button>
      <button class="btn" data-acao="exportar">${ICONES.baixar} Excel</button>
    </div>
  </div>
  <div class="filtros">
    <div class="chips">${PRESETS.map(([v, r]) => `<button class="chip ${S.filtro.preset === v && !S.filtro.mes ? 'on' : ''}" data-acao="preset" data-v="${v}">${r}</button>`).join('')}
      ${S.filtro.mes ? `<button class="chip on x" data-acao="limparMes" aria-label="Remover filtro do mês">${rotMes(S.filtro.mes)}</button>` : ''}</div>
    ${S.filtro.preset === 'pers' ? `<input type="month" id="fDe" value="${iv.de}" aria-label="De"> <input type="month" id="fAte" value="${iv.ate}" aria-label="Até">` : ''}
    <select id="fCat" aria-label="Categoria"><option value="">Todas as categorias</option>${cats.map(c => `<option ${c === S.filtro.categoria ? 'selected' : ''}>${esc(c)}</option>`).join('')}</select>
  </div>

  <section class="vitrine">
    <div class="resumo-topo">
      <div class="grande"><small>Gastos${S.filtro.mes ? ' em ' + rotMes(S.filtro.mes) : ''}</small>${brl(k.gastoTotal)}</div>
      ${k.faturamento > 0 ? `<div class="grande fat"><small>Vendas</small>${brl(k.faturamento)}</div>` : ''}
      <div class="legenda" style="margin-left:auto"><span><i style="background:#0F766E"></i>Gastos</span>${geral.serie.some(s => s.vendas > 0) ? '<span><i style="background:#B83D6E"></i>Vendas</span>' : ''}<span class="peq">Toque num mês para filtrar</span></div>
    </div>
    ${Graficos.meses(geral.serie, S.filtro.mes)}
  </section>
  ${blocoSetas(geral.serie)}

  <div class="kpis">
    ${kpi('Gasto total', brl(k.gastoTotal), cg.t, cg.c)}
    ${kpi('Faturamento', k.faturamento > 0 ? brl(k.faturamento) : '—', k.faturamento > 0 ? (k.faturamentoAnterior > 0 ? cf.t : `${k.vendas} vendas, ticket ${brl(k.ticketMedioVenda)}`) : 'registre as vendas para ver o lucro', k.faturamentoAnterior > 0 ? cf.c : '', 'fat')}
    ${kpi('Resultado (lucro)', k.faturamento > 0 ? brl(k.resultado) : '—', cr ? cr.t : (k.margem != null ? `margem de ${pct(k.margem)}` : 'vendas menos gastos'), cr ? cr.c : (k.resultado < 0 && k.faturamento > 0 ? 'ruim' : ''))}
    ${kpi('Custo de insumos sobre vendas', k.cmv != null ? pct(k.cmv) : '—', 'quanto das vendas vai para ingredientes')}
    ${S.filtro.mes ? kpi('Notas no mês', String(k.notas), `ticket médio ${brl(k.ticketMedio)}`) : kpi('Maior mês', k.maiorMes ? brl(k.maiorMes.valor) : '—', k.maiorMes ? k.maiorMes.rotulo : '')}
    ${S.filtro.mes ? kpi('Média mensal do período', brl(geral.kpis.mediaMensal), 'para comparar com este mês') : kpi('Média mensal', brl(k.mediaMensal), 'meses com gasto')}
    ${kpi('Fornecedor líder', k.fornecedorLider ? esc(k.fornecedorLider.nome) : '—', k.fornecedorLider ? `${brl(k.fornecedorLider.valor)}, ${pct(k.fornecedorLider.pct)} do total` : '', '', 'txt')}
    ${kpi('Ticket médio de compra', brl(k.ticketMedio), `${k.notas} notas, top 4 fornecedores = ${pct(k.concentracaoTop4)}`)}
  </div>

  <div class="grade g-painel">
    <div>
      ${blocoVis('fornecedores', 'Principais fornecedores', '', p.fornecedores, { cor: 'azul', acao: 'verFornecedor' })}
      ${blocoVis('pagamentos', 'Por forma de pagamento', '', p.pagamentos, { cor: 'agua' })}
      ${blocoVis('categorias', 'Por categoria', '', p.categorias, { acao: 'filtrarCategoria', max: 8 })}
    </div>
    <div>
      <section class="bloco"><h2>Principais descobertas</h2>
        ${p.descobertas.length ? `<ul class="descobertas">${p.descobertas.map(d => `<li>${esc(d)}</li>`).join('')}</ul>` : '<p class="sec">Sem dados suficientes no período.</p>'}</section>
      <section class="bloco"><h2>Preços dos insumos em alta</h2>
        ${p.insumosEmAlta.length ? `<ul class="lista">${p.insumosEmAlta.map(i => `<li data-acao="ir" data-para="insumo/${i.id}">
          <div class="meio"><div class="titulo">${esc(i.nome)}</div><div class="sec peq">agora ${brl(i.precoExib)}/${esc(i.unidadeExib)}</div></div>
          <span class="selo alta">+${pct(i.variacao)}</span></li>`).join('')}</ul>`
          : `<p class="sec">Nenhum aumento na última compra dos insumos cadastrados. ${S.dados.insumos.length ? '' : 'Cadastre insumos ao montar receitas para acompanhar os preços.'}</p>`}</section>
      <section class="bloco"><h2>O que mais pesou</h2>${Graficos.barras(p.produtos, { cor: 'cara', max: 8 })}</section>
    </div>
  </div>
  <p class="qualidade">${p.qualidade.map(esc).join(' ')}</p>`;
};
TELAS.resumo.depois = el => {
  $$('.rolar', el).forEach(r => r.scrollLeft = r.scrollWidth);
  const fc = $('#fCat', el); if (fc) fc.onchange = () => { S.filtro.categoria = fc.value; render(); };
  const de = $('#fDe', el), ate = $('#fAte', el);
  if (de) [de, ate].forEach(i => i.onchange = () => { S.filtro.de = de.value; S.filtro.ate = ate.value; render(); });
};

function kpi(rot, v, det, cls = '', tipo = '') {
  return `<div class="kpi ${tipo}"><div class="rot">${rot}</div><div class="v ${tipo === 'txt' ? 'txt' : ''}">${v}</div><div class="det ${cls}">${esc(det || '')}</div></div>`;
}

ACOES.preset = el => { S.filtro.preset = el.dataset.v; S.filtro.mes = ''; render(); };
ACOES.filtrarMes = el => { S.filtro.mes = S.filtro.mes === el.dataset.mes ? '' : el.dataset.mes; render(); };
ACOES.limparMes = () => { S.filtro.mes = ''; render(); };
ACOES.filtrarCategoria = el => { S.filtro.categoria = S.filtro.categoria === el.dataset.nome ? '' : el.dataset.nome; render(); };
ACOES.verFornecedor = el => ir('notas?loja=' + encodeURIComponent(el.dataset.nome));
ACOES.exportar = () => {
  const iv = intervaloDoFiltro();
  baixarExcel(S.filtro.mes ? { de: S.filtro.mes, ate: S.filtro.mes, categoria: S.filtro.categoria } : { ...iv, categoria: S.filtro.categoria });
};
