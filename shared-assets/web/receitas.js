/* Receitas (ficha técnica com custo) e insumos (preço tabelado vindo das notas). */
'use strict';

const UNIDADES_RECEITA = ['g', 'kg', 'mg', 'ml', 'l', 'un', 'dz', 'xícara', 'colher de sopa', 'colher de sobremesa', 'colher de chá', 'copo americano'];
const NOME_BASE = { g: 'gramas (g)', ml: 'mililitros (ml)', un: 'unidades' };
const abas = atual => `<div class="segmento" style="margin-bottom:14px">
  <button class="${atual === 'r' ? 'on' : ''}" data-acao="ir" data-para="receitas">Receitas</button>
  <button class="${atual === 'i' ? 'on' : ''}" data-acao="ir" data-para="insumos">Insumos e preços</button></div>`;

/* ---------------- lista de receitas ---------------- */
TELAS.receitas = () => {
  const rs = S.dados.receitas;
  return `
  <div class="cabeca"><div><h1>Receitas</h1><p class="sec">Custo e preço de venda calculados com os preços das suas notas.</p></div>
    <div class="acoes"><button class="btn pri" data-acao="ir" data-para="receita/nova">${ICONES.mais} Nova receita</button></div></div>
  ${abas('r')}
  ${rs.length ? `<div class="grade g3">${rs.map(r => { const c = r.calculo; return `
    <section class="bloco" data-acao="ir" data-para="receita/${r.id}" style="cursor:pointer">
      <header><h2>${esc(r.nome)}</h2>${c.completo ? '' : '<span class="selo aviso">falta informação</span>'}</header>
      <p style="font:700 24px var(--fonte-num);color:var(--framboesa)">${brl(c.precoVenda)}</p>
      <p class="sec">${brl(c.precoPorcao)} por ${esc(singular(c.rendimentoUnidade))}, ${numBR(c.rendimento)} ${esc(c.rendimentoUnidade)}</p>
      <p class="sec peq" style="margin-top:6px">Custo ${brl(c.custoTotal)}, margem de ${pct(c.margemSobrePreco)} sobre o preço</p>
    </section>`; }).join('')}</div>`
    : `<div class="bloco vazio"><h2>Monte sua primeira ficha técnica</h2>
        <p class="sec" style="max-width:48ch;margin:8px auto 0">Escolha os ingredientes direto das notas que você já importou. O app calcula o preço por kg, litro ou unidade e chega ao preço de venda.</p>
        <button class="btn pri" data-acao="ir" data-para="receita/nova">${ICONES.mais} Nova receita</button></div>`}`;
};
const singular = u => ({ fatias: 'fatia', unidades: 'unidade', porções: 'porção', potes: 'pote', bolos: 'bolo', docinhos: 'docinho', kg: 'kg' }[u] || u);

/* ---------------- editor de receita ---------------- */
TELAS.receita = r => {
  const existente = r.id !== 'nova' ? porId('receitas', r.id) : null;
  if (r.id !== 'nova' && !existente) return '<div class="vazio">Receita não encontrada.</div>';
  if (!S.receita || S.receita._rota !== r.id) {
    S.receita = existente ? JSON.parse(JSON.stringify(existente)) : {
      nome: '', rendimento: 1, rendimentoUnidade: 'unidades', ingredientes: [],
      maoDeObra: { modo: 'aditivo', valor: 0 }, margem: { modo: 'multiplicativo', valor: 100 } };
    delete S.receita.calculo;
    S.receita._rota = r.id;
    S.receitaPasso = existente ? 0 : 1;   // 0 = tudo visível (edição); 1..3 = assistente de nova receita
  }
  const R = S.receita, passo = S.receitaPasso;
  const ver = n => passo === 0 || passo >= n;
  return `
  <div class="cabeca"><div><h1>${existente ? esc(existente.nome) : 'Nova receita'}</h1>
    <p class="sec">${passo ? 'Monte a receita em três etapas.' : 'Altere o que precisar; o custo é recalculado na hora.'}</p></div>
    <div class="acoes">${existente ? `<button class="btn perigo" data-acao="excluirReceita">Excluir</button>` : ''}</div></div>
  ${passo ? `<div class="passos">${['Receita', 'Ingredientes', 'Mão de obra e margem'].map((t, i) =>
      `<button class="${passo === i + 1 ? 'on' : passo > i + 1 ? 'feito' : ''}" data-acao="passoReceita" data-p="${i + 1}" ${i + 1 > passo ? 'disabled' : ''}>${t}</button>`).join('')}</div>` : ''}
  <div class="receita-layout">
    <div>
      <section class="bloco">
        <h2>Receita</h2>
        <label class="campo"><span>Nome</span><input type="text" id="rNome" value="${esc(R.nome)}" placeholder="Ex.: Bolo de cenoura com brigadeiro"></label>
        <div class="linha-campos">
          <label class="campo"><span>Rendimento</span><input type="text" inputmode="decimal" id="rRend" value="${numBR(R.rendimento)}"></label>
          <label class="campo"><span>Unidade do rendimento</span><input type="text" id="rRendU" value="${esc(R.rendimentoUnidade)}" list="unidadesRend"></label>
        </div>
        <datalist id="unidadesRend"><option value="fatias"><option value="unidades"><option value="porções"><option value="potes"><option value="docinhos"><option value="kg"></datalist>
        ${passo === 1 ? `<button class="btn pri" data-acao="passoReceita" data-p="2">Continuar para os ingredientes</button>` : ''}
      </section>
      ${ver(2) ? `<section class="bloco"><header><h2>Ingredientes</h2><span class="sec peq">Quantidades usadas na receita</span></header>
        <div id="rIngs"></div>
        <div class="chips" style="margin-top:6px">
          <button class="btn" data-acao="ingDaNota">${ICONES.nota} Da nota fiscal</button>
          ${S.dados.insumos.length ? `<button class="btn" data-acao="ingDoInsumo">Insumo cadastrado</button>` : ''}
          <button class="btn lev" data-acao="ingManual">${ICONES.lapis} Com preço manual</button>
        </div>
        ${passo === 2 ? `<div style="margin-top:14px"><button class="btn pri" data-acao="passoReceita" data-p="3">Continuar para mão de obra e margem</button></div>` : ''}
      </section>` : ''}
      ${ver(3) ? `<section class="bloco" id="rAjustes">
        <h2>Mão de obra e margem de lucro</h2>
        <p class="sec" style="margin-bottom:12px">Para cada uma, escolha se soma um valor em reais (aditivo) ou aplica um percentual (multiplicativo).</p>
        ${blocoAjuste('maoDeObra', 'Mão de obra', 'Ex.: R$ 30 pelas horas de trabalho, ou 40% sobre o custo dos ingredientes.')}
        ${blocoAjuste('margem', 'Margem de lucro', 'Aplicada sobre o custo com mão de obra. Ex.: 100% dobra o custo.')}
      </section>` : ''}
      <div class="cabeca" style="justify-content:flex-end"><div class="acoes">
        <button class="btn" data-acao="cancelarReceita">Cancelar</button>
        ${ver(3) ? `<button class="btn pri" data-acao="salvarReceita">${existente ? 'Salvar alterações' : 'Salvar receita'}</button>` : ''}</div></div>
    </div>
    <aside class="bloco" id="rResumo"><p class="sec">Adicione ingredientes para ver o custo.</p></aside>
  </div>
  <div class="barra-preco" id="rBarra" hidden><span>Preço de venda <b id="rBarraPreco"></b></span><a href="#rResumo">ver conta</a></div>`;
};
TELAS.receita.editavel = true;
TELAS.receita.depois = el => {
  const R = S.receita;
  $('#rNome', el).oninput = e => { R.nome = e.target.value; };
  $('#rRend', el).oninput = e => { R.rendimento = lerNum(e.target.value) || 1; recalcular(); };
  $('#rRendU', el).oninput = e => { R.rendimentoUnidade = e.target.value; recalcular(); };
  ['maoDeObra', 'margem'].forEach(k => {
    const inp = $(`#aj_${k}`, el); if (!inp) return;
    inp.oninput = () => { R[k].valor = lerNum(inp.value) || 0; recalcular(); };
  });
  desenharIngredientes(); recalcular();
};

function blocoAjuste(k, titulo, ajuda) {
  const a = S.receita[k] || { modo: 'aditivo', valor: 0 };
  return `<div style="margin-bottom:16px">
    <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:6px"><b>${titulo}</b>
      <div class="segmento"><button class="${a.modo === 'aditivo' ? 'on' : ''}" data-acao="modoAjuste" data-k="${k}" data-m="aditivo">Aditivo (R$)</button>
      <button class="${a.modo === 'multiplicativo' ? 'on' : ''}" data-acao="modoAjuste" data-k="${k}" data-m="multiplicativo">Multiplicativo (%)</button></div></div>
    <div style="display:flex;gap:8px;align-items:center;max-width:260px">
      ${a.modo === 'aditivo' ? '<span class="sec">R$</span>' : ''}<input type="text" inputmode="decimal" id="aj_${k}" value="${numBR(a.valor, 2)}">${a.modo === 'multiplicativo' ? '<span class="sec">%</span>' : ''}</div>
    <p class="sec peq" style="margin-top:4px">${ajuda}</p></div>`;
}
ACOES.modoAjuste = el => {
  const k = el.dataset.k; S.receita[k] = { ...(S.receita[k] || {}), modo: el.dataset.m };
  const y = window.scrollY;
  render().then(() => { window.scrollTo(0, y); recalcular(); });
};
ACOES.passoReceita = el => {
  const p = +el.dataset.p;
  if (p >= 2 && !S.receita.nome.trim()) return toast('Dê um nome para a receita.', true);
  if (p === 3 && !S.receita.ingredientes.length) return toast('Adicione pelo menos um ingrediente.', true);
  S.receitaPasso = Math.max(S.receitaPasso, p);
  render().then(() => { const alvo = p === 2 ? $('#rIngs') : $('#rAjustes'); alvo && alvo.scrollIntoView({ behavior: 'smooth', block: 'start' }); });
};
ACOES.cancelarReceita = () => { S.receita = null; ir('receitas'); };
ACOES.salvarReceita = async () => {
  const R = S.receita;
  if (!R.nome.trim()) return toast('Dê um nome para a receita.', true);
  const { _rota, calculo, ...rec } = R;
  try { await salvar('receitas', rec); toast('Receita salva.'); S.receita = null; ir('receitas'); } catch (e) { falha(e); }
};
ACOES.excluirReceita = async () => {
  if (!await confirmar('Excluir receita?', S.receita.nome, 'Excluir', true)) return;
  await excluir('receitas', S.receita.id); S.receita = null; ir('receitas');
};

const recalcular = debounce(async () => {
  const box = $('#rResumo'); if (!box || !S.receita) return;
  try {
    const c = await GC.chamar('calcularReceita', { receita: S.receita });
    S.receita.calculo = c;
    c.ingredientes.forEach((l, k) => {
      const e = $(`#ingCusto${k}`); if (e) e.textContent = l.custo != null ? brl(l.custo) : '—';
      const a = $(`#ingAviso${k}`); if (a) a.innerHTML = avisoIngrediente(l, k);
      const p = $(`#ingPreco${k}`); if (p) p.textContent = l.precoExib != null ? `${brl(l.precoExib)}/${l.unidadeExib}` : '';
    });
    const mo = S.receita.maoDeObra || {}, mg = S.receita.margem || {};
    const barra = $('#rBarra'); if (barra) { barra.hidden = !c.ingredientes.length; $('#rBarraPreco').textContent = brl(c.precoVenda); }
    box.innerHTML = `<h2>Custo e preço</h2>
      <table class="conta">
        <tr><td><span class="op"></span>Ingredientes</td><td>${brl(c.custoIngredientes)}</td></tr>
        <tr><td><span class="op">+</span> Mão de obra ${mo.modo === 'multiplicativo' ? `(${numBR(mo.valor, 2)}%)` : ''}</td><td>${brl(c.maoDeObra)}</td></tr>
        <tr class="total"><td><span class="op">=</span> Custo total</td><td>${brl(c.custoTotal)}</td></tr>
        <tr><td><span class="op">+</span> Margem ${mg.modo === 'multiplicativo' ? `(${numBR(mg.valor, 2)}%)` : ''}</td><td>${brl(c.lucro)}</td></tr>
        <tr class="total preco"><td><span class="op">=</span> Preço de venda</td><td>${brl(c.precoVenda)}</td></tr>
      </table>
      <p class="sec" style="margin-top:10px">${brl(c.precoPorcao)} por ${esc(singular(c.rendimentoUnidade))} (custo ${brl(c.custoPorcao)})</p>
      <p class="sec">Margem sobre o preço: ${pct(c.margemSobrePreco)}</p>
      ${c.completo ? '' : `<p class="aviso-caixa" style="margin-top:10px">Alguns ingredientes estão sem custo. Resolva os avisos para o preço ficar correto.</p>`}`;
  } catch (e) { box.innerHTML = `<p class="sec">${esc(e.message)}</p>`; }
}, 200);

function desenharIngredientes() {
  const box = $('#rIngs'); if (!box) return;
  const ings = S.receita.ingredientes;
  if (!ings.length) { box.innerHTML = '<p class="sec" style="margin-bottom:8px">Nenhum ingrediente ainda.</p>'; return; }
  box.innerHTML = ings.map((g, k) => {
    const ins = g.insumoId ? porId('insumos', g.insumoId) : null;
    return `<div class="ing" data-k="${k}">
      <div class="l1"><input type="text" class="nome" value="${esc(g.nome || (ins && ins.nome) || 'Ingrediente')}" aria-label="Nome do ingrediente">
        <span class="sec peq" id="ingPreco${k}"></span>
        <button class="btn lev peq" data-acao="remIng" data-k="${k}" aria-label="Remover">✕</button></div>
      <div class="l2"><input type="text" inputmode="decimal" class="iq" value="${numBR(g.quantidade)}" aria-label="Quantidade">
        <select class="iu" aria-label="Unidade">${UNIDADES_RECEITA.map(u => `<option ${u === g.unidade ? 'selected' : ''}>${u}</option>`).join('')}</select>
        <span class="custo" id="ingCusto${k}">—</span></div>
      <div id="ingAviso${k}"></div></div>`;
  }).join('');
  $$('.ing', box).forEach(div => {
    const k = +div.dataset.k;
    $('.iq', div).oninput = e => { ings[k].quantidade = lerNum(e.target.value) || 0; recalcular(); };
    $('input.nome', div).oninput = e => { ings[k].nome = e.target.value; };
    $('.iu', div).onchange = e => { ings[k].unidade = e.target.value; recalcular(); };
  });
}
ACOES.remIng = el => { S.receita.ingredientes.splice(+el.dataset.k, 1); desenharIngredientes(); recalcular(); };

function avisoIngrediente(l, k) {
  if (!l.aviso) return '';
  const botao = {
    unidade_compra: `<button class="btn peq" data-acao="resolverUnidade" data-k="${k}">Informar a embalagem</button>`,
    equivalencia: `<button class="btn peq" data-acao="resolverEquivalencia" data-k="${k}">Informar a equivalência</button>`,
    sem_preco: `<button class="btn peq" data-acao="resolverPreco" data-k="${k}">Informar o preço</button>`,
  }[l.tipoAviso] || '';
  return `<div class="aviso-caixa">${esc(l.aviso)}<br>${botao}</div>`;
}

/* ---------- adicionar ingredientes ---------- */
ACOES.ingDaNota = () => modal(`<h2>Ingrediente da nota fiscal</h2>
  <p class="sec">Busque o produto como aparece nas notas. O preço é tabelado pela embalagem (ex.: 1 kg).</p>
  <input type="search" id="mBusca" placeholder="Ex.: chocolate, leite cond, manteiga" style="margin:10px 0">
  <ul class="lista" id="mProds"><li class="sec" style="cursor:default">Digite para buscar…</li></ul>`,
  (el, fechar) => {
    const buscar = debounce(async () => {
      const lista = await GC.chamar('produtos', { busca: $('#mBusca', el).value });
      $('#mProds', el).innerHTML = lista.length ? lista.slice(0, 40).map((p, i) => `<li data-i="${i}">
        <div class="meio"><div class="titulo">${esc(p.descricao)}</div>
          <div class="sec peq">${dataBR(p.ultimaData)}, ${esc(p.ultimaLoja)}, ${numBR(p.ultimaQuantidade)} ${esc(p.unidade || 'un')} por ${brl(p.ultimoValor)}</div></div>
        ${p.precoExib != null ? `<span class="selo ok">${brl(p.precoExib)}/${esc(p.unidadeExib)}</span>` : '<span class="selo aviso">unidade?</span>'}</li>`).join('')
        : '<li class="sec" style="cursor:default">Nada encontrado nas notas.</li>';
      $$('#mProds li[data-i]', el).forEach(li => li.onclick = () => { fechar(); escolherProduto(lista[+li.dataset.i]); });
    }, 250);
    $('#mBusca', el).oninput = buscar; buscar();
  });

/** Ao escolher um produto: se a unidade não foi identificada, pede ao usuário antes de criar o insumo. */
async function escolherProduto(p) {
  let conteudo = null;
  if (!p.conteudo) {
    conteudo = await pedirConteudo(p.descricao, p.sugestao);
    if (!conteudo) return;
  }
  try {
    const ins = await GC.chamar('insumoDeProduto', { chave: p.chave, nome: nomeAmigavel(p.descricao), insumoId: p.insumoId || '', conteudo });
    await recarregar();
    const base = ins.base || 'g';
    S.receita.ingredientes.push({ insumoId: ins.id, nome: ins.nome, quantidade: base === 'un' ? 1 : 100, unidade: base });
    desenharIngredientes(); recalcular();
    const ult = $$('.ing .iq').pop(); if (ult) { ult.focus(); ult.select(); }
  } catch (e) { falha(e); }
}
const nomeAmigavel = d => { const s = d.toLowerCase().replace(/\./g, ' ').replace(/\s+/g, ' ').trim(); return s.charAt(0).toUpperCase() + s.slice(1); };

/** Modal "não identifiquei a unidade". Resolve com {valor, base} ou null. */
function pedirConteudo(descricao, sugestao) {
  return new Promise(res => {
    let ok = false;
    const fechar = modal(`<h2>Quanto vem na embalagem?</h2>
      <div class="aviso-caixa">Não identifiquei a unidade de medida em <b>${esc(descricao)}</b>. Informe o conteúdo de 1 unidade comprada para o preço ser tabelado.</div>
      <div class="linha-campos" style="margin-top:12px">
        <label class="campo"><span>1 unidade tem</span><input type="text" inputmode="decimal" id="mVal" value="${sugestao ? numBR(sugestao.valor) : ''}" placeholder="395"></label>
        <label class="campo"><span>Medida</span><select id="mBase"><option value="g">gramas (g)</option><option value="kg">quilos (kg)</option><option value="ml">mililitros (ml)</option><option value="l">litros (L)</option><option value="un">unidades</option></select></label>
      </div>
      ${sugestao ? `<p class="sec peq">Sugestão pelo número na descrição: ${numBR(sugestao.valor)} g. Confira na embalagem.</p>` : ''}
      <div class="rodape"><button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="mOk">Usar este conteúdo</button></div>`,
      (el, f) => {
        $('#mOk', el).onclick = () => {
          const v = lerNum($('#mVal', el).value); const b = $('#mBase', el).value;
          if (!v || v <= 0) return toast('Informe um número maior que zero.', true);
          ok = true; f();
          res(b === 'kg' ? { valor: v * 1000, base: 'g' } : b === 'l' ? { valor: v * 1000, base: 'ml' } : { valor: v, base: b });
        };
      });
    const obs = new MutationObserver(() => { if (!document.querySelector('#mVal')) { obs.disconnect(); if (!ok) res(null); } });
    obs.observe(document.body, { childList: true });
  });
}

ACOES.ingDoInsumo = () => modal(`<h2>Insumo cadastrado</h2>
  <ul class="lista">${S.dados.insumos.map((i, k) => `<li data-i="${k}"><div class="meio"><div class="titulo">${esc(i.nome)}</div></div>
    <span class="selo ${i.precoExib != null ? 'ok' : 'aviso'}">${i.precoExib != null ? brl(i.precoExib) + '/' + esc(i.unidadeExib) : 'sem preço'}</span></li>`).join('')}</ul>`,
  (el, fechar) => $$('li[data-i]', el).forEach(li => li.onclick = () => {
    const ins = S.dados.insumos[+li.dataset.i]; fechar();
    S.receita.ingredientes.push({ insumoId: ins.id, nome: ins.nome, quantidade: ins.base === 'un' ? 1 : 100, unidade: ins.base || 'g' });
    desenharIngredientes(); recalcular();
  }));

ACOES.ingManual = () => modal(`<h2>Ingrediente com preço manual</h2>
  <p class="sec">Para algo que não está em nenhuma nota (ex.: comprado na feira).</p>
  <label class="campo"><span>Nome</span><input type="text" id="mN" placeholder="Ex.: Ovos caipira"></label>
  <p class="sec" style="margin-bottom:6px">Quanto você pagou:</p>
  <div class="linha-campos">
    <label class="campo"><span>Valor (R$)</span><input type="text" inputmode="decimal" id="mV" placeholder="18,00"></label>
    <label class="campo"><span>por</span><input type="text" inputmode="decimal" id="mQ" value="1"></label>
    <label class="campo"><span>Unidade</span><select id="mU">${['kg', 'g', 'l', 'ml', 'un', 'dz'].map(u => `<option>${u}</option>`).join('')}</select></label>
  </div>
  <div class="rodape"><button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="mOk">Adicionar</button></div>`,
  (el, fechar) => $('#mOk', el).onclick = () => {
    const nome = $('#mN', el).value.trim(), v = lerNum($('#mV', el).value), q = lerNum($('#mQ', el).value) || 1, u = $('#mU', el).value;
    if (!nome || !v) return toast('Informe o nome e o valor pago.', true);
    fechar();
    const base = ['kg', 'g'].includes(u) ? 'g' : ['l', 'ml'].includes(u) ? 'ml' : 'un';
    S.receita.ingredientes.push({ nome, quantidade: base === 'un' ? 1 : 100, unidade: base, precoManual: { valor: v, quantidade: q, unidade: u } });
    desenharIngredientes(); recalcular();
  });

/* ---------- resolver avisos ---------- */
ACOES.resolverUnidade = async el => {
  const ing = S.receita.ingredientes[+el.dataset.k];
  const ins = porId('insumos', ing.insumoId); if (!ins) return;
  const pend = (ins.pendencias || [])[0]; if (!pend) return;
  const c = await pedirConteudo(pend.descricao, pend.sugestao); if (!c) return;
  try { await GC.chamar('insumoDeProduto', { chave: pend.chave, nome: '', insumoId: ins.id, conteudo: c }); await recarregar(); recalcular(); toast('Embalagem registrada. Vale para as próximas compras deste produto.'); } catch (e) { falha(e); }
};
ACOES.resolverEquivalencia = el => {
  const k = +el.dataset.k, ing = S.receita.ingredientes[k];
  const ins = ing.insumoId ? porId('insumos', ing.insumoId) : null;
  const usaUn = ing.unidade === 'un' || ing.unidade === 'dz' || (ins && ins.base === 'un') || (ing.precoManual && ['un', 'dz'].includes(ing.precoManual.unidade));
  modal(`<h2>Equivalência</h2>
    <p class="sec">${usaUn ? 'Quantos gramas tem 1 unidade deste ingrediente?' : 'Quantos gramas tem 1 ml deste ingrediente? (água e leite ≈ 1; óleo ≈ 0,92; mel ≈ 1,4)'}</p>
    <label class="campo"><span>${usaUn ? 'Gramas por unidade' : 'Gramas por ml'}</span><input type="text" inputmode="decimal" id="mE" value="${usaUn ? '' : '1'}"></label>
    <div class="rodape"><button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="mOk">Salvar</button></div>`,
    (m, fechar) => $('#mOk', m).onclick = async () => {
      const v = lerNum($('#mE', m).value); if (!v || v <= 0) return toast('Informe um número maior que zero.', true);
      const campo = usaUn ? 'gPorUn' : 'gPorMl';
      fechar();
      if (ins) { const { pendencias, compras, ultimaCompra, precoBase, precoExib, mediaExib, ultimoExib, variacao, unidadeExib, ...limpo } = ins; await salvar('insumos', { ...limpo, [campo]: v }); }
      else ing[campo] = v;
      recalcular();
    });
};
ACOES.resolverPreco = el => {
  const ing = S.receita.ingredientes[+el.dataset.k];
  const ins = porId('insumos', ing.insumoId);
  if (ins) return ir('insumo/' + ins.id);
};

/* ---------------- insumos ---------------- */
TELAS.insumos = () => {
  const lista = S.dados.insumos;
  return `<div class="cabeca"><div><h1>Insumos e preços</h1><p class="sec">Preço tabelado por kg, litro ou unidade, atualizado a cada nova compra.</p></div>
    <div class="acoes"><button class="btn" data-acao="novoInsumo">${ICONES.mais} Insumo sem nota</button></div></div>
  ${abas('i')}
  <div class="bloco">${lista.length ? `<ul class="lista">${lista.map(i => `<li data-acao="ir" data-para="insumo/${i.id}">
      <div class="meio"><div class="titulo">${esc(i.nome)}</div>
        <div class="sec peq">${i.ultimaCompra ? `última compra ${dataBR(i.ultimaCompra.data)}, ${esc(i.ultimaCompra.loja)}` : 'sem compras com unidade identificada'}</div></div>
      ${(i.pendencias || []).length ? '<span class="selo aviso">unidade?</span>' : ''}
      ${i.variacao != null && Math.abs(i.variacao) > 0.0005 ? `<span class="selo ${i.variacao > 0 ? 'alta' : 'baixa'}">${i.variacao > 0 ? '+' : '−'}${pct(Math.abs(i.variacao))}</span>` : ''}
      <div class="valor">${i.precoExib != null ? brl(i.precoExib) + '<span class="sec peq">/' + esc(i.unidadeExib) + '</span>' : '—'}</div></li>`).join('')}</ul>`
    : '<div class="vazio"><p>Os insumos são criados quando você escolhe um ingrediente da nota numa receita.</p></div>'}</div>`;
};

TELAS.insumo = async r => {
  const i = await GC.chamar('insumo', { id: r.id });
  if (!i) return '<div class="vazio">Insumo não encontrado.</div>';
  S.insumo = i;
  const metodos = [['ultimo', 'Última compra'], ['media', 'Média ponderada'], ['manual', 'Preço manual']];
  return `<div class="cabeca"><div><h1>${esc(i.nome)}</h1><p class="sec">${i.precoExib != null ? `${brl(i.precoExib)} por ${esc(i.unidadeExib)}` : 'sem preço definido'}</p></div>
    <div class="acoes"><button class="btn perigo" data-acao="excluirInsumo">Excluir</button></div></div>
  <div class="grade g2">
    <section class="bloco"><h2>Preço usado nas receitas</h2>
      <label class="campo"><span>Nome</span><input type="text" id="iNome" value="${esc(i.nome)}"></label>
      <div class="segmento" style="margin-bottom:10px">${metodos.map(([v, t]) => `<button class="${(i.metodoPreco || 'ultimo') === v ? 'on' : ''}" data-acao="metodoInsumo" data-v="${v}">${t}</button>`).join('')}</div>
      <table class="conta"><tr><td>Última compra</td><td>${i.ultimoExib != null ? brl(i.ultimoExib) + '/' + esc(i.unidadeExib) : '—'}</td></tr>
        <tr><td>Média ponderada</td><td>${i.mediaExib != null ? brl(i.mediaExib) + '/' + esc(i.unidadeExib) : '—'}</td></tr></table>
      <label class="campo" style="margin-top:10px"><span>Preço manual (R$ por ${esc(i.unidadeExib)})</span><input type="text" inputmode="decimal" id="iManual" value="${i.precoManual != null ? numBR(i.precoManual, 2) : ''}"></label>
      <div class="linha-campos">
        <label class="campo"><span>Gramas por ml (densidade)</span><input type="text" inputmode="decimal" id="iGml" value="${numBR(i.gPorMl)}" placeholder="opcional"></label>
        <label class="campo"><span>Gramas por unidade</span><input type="text" inputmode="decimal" id="iGun" value="${numBR(i.gPorUn)}" placeholder="opcional"></label>
      </div>
      <button class="btn pri" data-acao="salvarInsumo">Salvar</button>
    </section>
    <section class="bloco"><header><h2>Produtos das notas</h2><button class="btn lev peq" data-acao="vincularProduto">${ICONES.mais} Vincular outro</button></header>
      ${(i.pendencias || []).map(p => `<div class="aviso-caixa" style="margin-bottom:8px">Não identifiquei a unidade de <b>${esc(p.descricao)}</b>.<br>
        <button class="btn peq" data-acao="informarEmbalagem" data-chave="${esc(p.chave)}" data-desc="${esc(p.descricao)}">Informar a embalagem</button></div>`).join('')}
      <ul class="lista">${(i.vinculos || []).map(v => `<li style="cursor:default"><div class="meio"><div class="titulo">${esc(v)}</div>
        <div class="sec peq">${i.conteudos && i.conteudos[v] ? `1 unidade = ${numBR(i.conteudos[v].valor)} ${i.conteudos[v].base} (informado)` : 'conteúdo lido da descrição'}</div></div>
        <button class="btn lev peq" data-acao="informarEmbalagem" data-chave="${esc(v)}" data-desc="${esc(v)}">Corrigir</button></li>`).join('')}</ul>
    </section>
  </div>
  <section class="bloco" style="margin-top:14px"><h2>Histórico de compras</h2>
    ${(i.compras || []).length ? `<div class="rolar"><table class="tab"><thead><tr><th>Data</th><th>Loja</th><th>Produto</th><th class="n">Qtd.</th><th class="n">Pago</th><th class="n">Preço/${esc(i.unidadeExib)}</th></tr></thead>
    <tbody>${i.compras.map(c => `<tr><td>${dataBR(c.data)}</td><td>${esc(c.loja)}</td><td>${esc(c.descricao)}</td><td class="n">${numBR(c.quantidade)} ${esc(c.unidade || '')}</td>
      <td class="n">${brl(c.valor)}</td><td class="n">${c.precoExib != null ? brl(c.precoExib) : '—'}</td></tr>`).join('')}</tbody></table></div>` : '<p class="sec">Sem compras.</p>'}
  </section>`;
};
TELAS.insumo.editavel = true;

function insumoLimpo() {
  const { pendencias, compras, ultimaCompra, precoBase, precoExib, mediaExib, ultimoExib, variacao, unidadeExib, ...limpo } = S.insumo;
  return limpo;
}
ACOES.metodoInsumo = async el => { try { await salvar('insumos', { ...insumoLimpo(), metodoPreco: el.dataset.v }); render(); } catch (e) { falha(e); } };
ACOES.salvarInsumo = async () => {
  const rec = { ...insumoLimpo(), nome: $('#iNome').value.trim() || S.insumo.nome, precoManual: lerNum($('#iManual').value), gPorMl: lerNum($('#iGml').value), gPorUn: lerNum($('#iGun').value) };
  if (rec.precoManual != null && rec.metodoPreco !== 'manual' && S.insumo.precoExib == null) rec.metodoPreco = 'manual';
  try { await salvar('insumos', rec); toast('Insumo salvo.'); render(); } catch (e) { falha(e); }
};
ACOES.excluirInsumo = async () => {
  const usado = S.dados.receitas.filter(r => (r.ingredientes || []).some(g => g.insumoId === S.insumo.id));
  if (!await confirmar('Excluir insumo?', usado.length ? `Ele é usado em ${usado.length} receita(s), que ficarão sem preço para este ingrediente.` : 'As notas continuam no app.', 'Excluir', true)) return;
  await excluir('insumos', S.insumo.id); ir('insumos');
};
ACOES.informarEmbalagem = async el => {
  const c = await pedirConteudo(el.dataset.desc, null); if (!c) return;
  try { await GC.chamar('insumoDeProduto', { chave: el.dataset.chave, nome: '', insumoId: S.insumo.id, conteudo: c }); await recarregar(); render(); } catch (e) { falha(e); }
};
ACOES.vincularProduto = () => modal(`<h2>Vincular produto</h2><p class="sec">Outras marcas ou descrições do mesmo insumo entram no preço.</p>
  <input type="search" id="mBusca" placeholder="Buscar nas notas" style="margin:10px 0"><ul class="lista" id="mProds"></ul>`,
  (el, fechar) => {
    const buscar = debounce(async () => {
      const lista = (await GC.chamar('produtos', { busca: $('#mBusca', el).value })).filter(p => p.insumoId !== S.insumo.id);
      $('#mProds', el).innerHTML = lista.slice(0, 40).map((p, i) => `<li data-i="${i}"><div class="meio"><div class="titulo">${esc(p.descricao)}</div>
        <div class="sec peq">${esc(p.ultimaLoja)}, ${dataBR(p.ultimaData)}</div></div></li>`).join('') || '<li class="sec">Nada encontrado.</li>';
      $$('#mProds li[data-i]', el).forEach(li => li.onclick = async () => {
        const p = lista[+li.dataset.i]; fechar();
        let conteudo = null; if (!p.conteudo) { conteudo = await pedirConteudo(p.descricao, p.sugestao); if (!conteudo) return; }
        await GC.chamar('insumoDeProduto', { chave: p.chave, nome: '', insumoId: S.insumo.id, conteudo }); await recarregar(); render();
      });
    }, 250);
    $('#mBusca', el).oninput = buscar; buscar();
  });
ACOES.novoInsumo = () => modal(`<h2>Insumo sem nota</h2>
  <label class="campo"><span>Nome</span><input type="text" id="mN"></label>
  <div class="linha-campos"><label class="campo"><span>Preço (R$)</span><input type="text" inputmode="decimal" id="mP"></label>
  <label class="campo"><span>por</span><select id="mB"><option value="g">kg</option><option value="ml">litro</option><option value="un">unidade</option></select></label></div>
  <div class="rodape"><button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="mOk">Criar</button></div>`,
  (el, fechar) => $('#mOk', el).onclick = async () => {
    const nome = $('#mN', el).value.trim(); if (!nome) return toast('Informe o nome.', true);
    fechar();
    const r = await salvar('insumos', { nome, base: $('#mB', el).value, metodoPreco: 'manual', precoManual: lerNum($('#mP', el).value), vinculos: [] });
    ir('insumo/' + r.id);
  });
