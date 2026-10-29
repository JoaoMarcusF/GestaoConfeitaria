/* Resumo: painel dinâmico com indicadores financeiros. */
'use strict';

S.filtro = { preset: '12m', categoria: '', mes: '' };

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
  const comp = (a, b) => b > 0 ? { t: `${a >= b ? '+' : '−'}${pct(Math.abs(a / b - 1))} vs período anterior`, c: a >= b ? 'sobe' : 'desce' } : { t: 'sem período anterior', c: '' };
  const cg = comp(k.gastoTotal, k.gastoAnterior);
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

  <div class="kpis">
    ${kpi('Gasto total', brl(k.gastoTotal), cg.t, cg.c)}
    ${kpi('Faturamento', k.faturamento > 0 ? brl(k.faturamento) : '—', k.faturamento > 0 ? `${k.vendas} vendas, ticket ${brl(k.ticketMedioVenda)}` : 'registre as vendas para ver o lucro', '', 'fat')}
    ${kpi('Resultado', k.faturamento > 0 ? brl(k.resultado) : '—', k.margem != null ? `margem de ${pct(k.margem)}` : 'vendas menos gastos', k.resultado < 0 && k.faturamento > 0 ? 'sobe' : '')}
    ${kpi('Custo de insumos sobre vendas', k.cmv != null ? pct(k.cmv) : '—', 'quanto das vendas vai para ingredientes')}
    ${S.filtro.mes ? kpi('Notas no mês', String(k.notas), `ticket médio ${brl(k.ticketMedio)}`) : kpi('Maior mês', k.maiorMes ? brl(k.maiorMes.valor) : '—', k.maiorMes ? k.maiorMes.rotulo : '')}
    ${S.filtro.mes ? kpi('Média mensal do período', brl(geral.kpis.mediaMensal), 'para comparar com este mês') : kpi('Média mensal', brl(k.mediaMensal), 'meses com gasto')}
    ${kpi('Fornecedor líder', k.fornecedorLider ? esc(k.fornecedorLider.nome) : '—', k.fornecedorLider ? `${brl(k.fornecedorLider.valor)}, ${pct(k.fornecedorLider.pct)} do total` : '', '', 'txt')}
    ${kpi('Ticket médio de compra', brl(k.ticketMedio), `${k.notas} notas, top 4 fornecedores = ${pct(k.concentracaoTop4)}`)}
  </div>

  <div class="grade g-painel">
    <div>
      <section class="bloco"><header><h2>Principais fornecedores</h2><span class="sec peq">toque para ver as notas</span></header>
        ${Graficos.barras(p.fornecedores, { cor: 'azul', acao: 'verFornecedor' })}</section>
      <section class="bloco"><h2>Por forma de pagamento</h2>${Graficos.barras(p.pagamentos, { cor: 'agua' })}</section>
      <section class="bloco"><header><h2>Por categoria</h2><span class="sec peq">toque para filtrar</span></header>
        ${Graficos.barras(p.categorias, { acao: 'filtrarCategoria', max: 8 })}</section>
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
  const rolar = $('.vitrine .rolar', el); if (rolar) rolar.scrollLeft = rolar.scrollWidth;
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
