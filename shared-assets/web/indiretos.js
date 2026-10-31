/* Gastos indiretos: tarifas (luz, gás), equipamentos e gastos salvos por categoria. */
'use strict';

const FONTES = {
  eletrico: { nome: 'Elétrico', campo: 'potenciaW', rot: 'Potência (W)', ex: 'Está na etiqueta ou no manual. Ex.: forno elétrico de bancada 1500 W, batedeira 600 W.' },
  glp: { nome: 'Gás de botijão', campo: 'consumo', rot: 'Consumo de gás (kg/h)', ex: 'A etiqueta do Inmetro do fogão informa o consumo de cada boca e do forno em kg/h.' },
  encanado: { nome: 'Gás encanado', campo: 'consumo', rot: 'Consumo de gás (m³/h)', ex: 'Veja no manual do fogão o consumo em m³/h.' },
  hora: { nome: 'Custo fixo por hora', campo: 'custoHora', rot: 'Custo por hora (R$)', ex: 'Para quando você já sabe quanto custa uma hora de uso.' },
};
const especEquip = e => ({ eletrico: `${numBR(e.potenciaW)} W`, glp: `${numBR(e.consumo)} kg/h`, encanado: `${numBR(e.consumo)} m³/h`, hora: `${brl(e.custoHora)}/h` }[e.fonte] || '')
  + (e.fonte !== 'hora' && e.usoPct && e.usoPct !== 100 ? `, ${numBR(e.usoPct)}% do tempo` : '');

TELAS.indiretos = () => {
  const t = S.dados.tarifas || {};
  const eqs = S.dados.equipamentos, mods = S.dados.gastosIndiretos;
  const grupos = {};
  mods.forEach(m => (grupos[m.categoria || 'Outros'] = grupos[m.categoria || 'Outros'] || []).push(m));
  return `
  <div class="cabeca"><div><h1>Gastos indiretos</h1><p class="sec">Luz, gás e outras contas que entram no custo das receitas.</p></div></div>
  ${abas('g')}
  <div class="grade g2">
    <section class="bloco"><h2>Tarifas</h2>
      <p class="sec" style="margin-bottom:10px">Usadas para calcular o custo dos equipamentos.</p>
      <label class="campo"><span>Energia elétrica (R$ por kWh)</span>
        <div style="display:flex;gap:8px"><input type="text" inputmode="decimal" id="tKwh" value="${numBR(t.energiaKwh, 4)}" placeholder="0,95">
        <button class="btn peq" data-acao="calcTarifa" data-tipo="luz">Calcular pela conta</button></div></label>
      <div class="linha-campos">
        <label class="campo"><span>Botijão de gás (R$)</span><input type="text" inputmode="decimal" id="tBot" value="${numBR(t.botijaoPreco, 2)}" placeholder="110,00"></label>
        <label class="campo"><span>Peso do botijão (kg)</span><input type="text" inputmode="decimal" id="tBotKg" value="${numBR(t.botijaoKg || 13)}"></label>
      </div>
      <label class="campo"><span>Gás encanado (R$ por m³)</span>
        <div style="display:flex;gap:8px"><input type="text" inputmode="decimal" id="tM3" value="${numBR(t.gasM3, 4)}" placeholder="opcional">
        <button class="btn peq" data-acao="calcTarifa" data-tipo="gas">Calcular pela conta</button></div></label>
      <button class="btn pri" data-acao="salvarTarifas">Salvar tarifas</button>
    </section>
    <section class="bloco"><header><h2>Equipamentos</h2><button class="btn lev peq" data-acao="novoEquip">${ICONES.mais} Equipamento</button></header>
      ${eqs.length ? `<ul class="lista">${eqs.map(e => `<li data-acao="editarEquip" data-id="${e.id}">
        <div class="meio"><div class="titulo">${esc(e.nome)}</div><div class="sec peq">${FONTES[e.fonte] ? FONTES[e.fonte].nome : ''}, ${especEquip(e)}</div></div>
        ${e.custoHora != null ? `<div class="valor">${brl(e.custoHora)}<span class="sec peq">/h</span></div>` : `<span class="selo aviso" title="${esc(e.aviso || '')}">falta tarifa</span>`}</li>`).join('')}</ul>`
      : '<p class="sec">Cadastre o forno, o fogão, a batedeira… Na receita você informa só por quantos minutos cada um fica ligado.</p>'}
    </section>
  </div>
  <section class="bloco" style="margin-top:14px"><header><h2>Gastos salvos</h2><button class="btn lev peq" data-acao="novoModelo">${ICONES.mais} Gasto salvo</button></header>
    <p class="sec" style="margin-bottom:6px">Gastos que se repetem, organizados por categoria, para adicionar às receitas com um toque.</p>
    ${mods.length ? Object.keys(grupos).sort().map(c => `<div class="mes-sep"><span>${esc(c)}</span><span>${grupos[c].length}</span></div>
      <ul class="lista">${grupos[c].map(m => `<li data-acao="editarModelo" data-id="${m.id}"><div class="meio"><div class="titulo">${esc(m.nome)}</div>
        <div class="sec peq">${descModelo(m)}</div></div></li>`).join('')}</ul>`).join('')
    : '<p class="sec">Nenhum ainda. Na receita, use o botão Salvar de um gasto indireto, ou crie aqui.</p>'}
  </section>
  <section class="bloco" style="margin-top:14px"><h2>Categorias de gasto indireto</h2>
    <div class="chips" style="margin:6px 0 10px">${CAT_INDIRETAS().map(c => `<span class="chip">${esc(c)} <button class="btn lev peq" data-acao="remCatInd" data-c="${esc(c)}" aria-label="Remover ${esc(c)}" style="padding:0 2px">✕</button></span>`).join('')}</div>
    <div style="display:flex;gap:8px;max-width:420px"><input type="text" id="novaCatInd" placeholder="Nova categoria (ex.: Aluguel da cozinha)"><button class="btn" data-acao="addCatInd">Adicionar</button></div>
  </section>`;
};
TELAS.indiretos.editavel = true;

async function salvarTarifas(alt = {}) {
  const t = S.dados.tarifas || {};
  await salvar('config', { ...t, id: 'tarifas', ...alt });
}
ACOES.salvarTarifas = async () => {
  try {
    await salvarTarifas({ energiaKwh: lerNum($('#tKwh').value), botijaoPreco: lerNum($('#tBot').value), botijaoKg: lerNum($('#tBotKg').value) || 13, gasM3: lerNum($('#tM3').value) });
    toast('Tarifas salvas. Os custos das receitas foram atualizados.'); render();
  } catch (e) { falha(e); }
};
ACOES.calcTarifa = el => {
  const luz = el.dataset.tipo === 'luz';
  modal(`<h2>${luz ? 'Tarifa pela conta de luz' : 'Tarifa pela conta de gás'}</h2>
    <p class="sec">${luz ? 'Divide o valor total da conta (com impostos e bandeira) pelo consumo do mês.' : 'Divide o valor total da conta pelo consumo do mês em m³.'}</p>
    <div class="linha-campos" style="margin-top:10px">
      <label class="campo"><span>Valor total da conta (R$)</span><input type="text" inputmode="decimal" id="mV"></label>
      <label class="campo"><span>Consumo (${luz ? 'kWh' : 'm³'})</span><input type="text" inputmode="decimal" id="mC"></label>
    </div><p class="sec" id="mR"></p>
    <div class="rodape"><button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="mOk">Usar este valor</button></div>`, (m, fechar) => {
    const calc = () => { const v = lerNum($('#mV', m).value), c = lerNum($('#mC', m).value); return v && c ? v / c : null; };
    ['#mV', '#mC'].forEach(s => $(s, m).oninput = () => { const r = calc(); $('#mR', m).textContent = r ? `R$ ${numBR(r, 4)} por ${luz ? 'kWh' : 'm³'}` : ''; });
    $('#mOk', m).onclick = () => { const r = calc(); if (!r) return toast('Informe o valor e o consumo.', true); fechar(); $(luz ? '#tKwh' : '#tM3').value = numBR(r, 4); toast('Confira e toque em Salvar tarifas.'); };
  });
};

/* ---------- equipamentos ---------- */
ACOES.novoEquip = () => editarEquipamento(null);
ACOES.editarEquip = el => editarEquipamento(porId('equipamentos', el.dataset.id));

function editarEquipamento(eq, aoSalvar) {
  const e = eq ? { ...eq } : { nome: '', fonte: 'eletrico', usoPct: 100 };
  modal(`<h2>${eq ? 'Editar equipamento' : 'Novo equipamento'}</h2>
    <label class="campo"><span>Nome</span><input type="text" id="eNome" value="${esc(e.nome)}" placeholder="Ex.: Forno elétrico, Fogão (boca grande)"></label>
    <label class="campo"><span>Tipo</span><select id="eFonte">${Object.entries(FONTES).map(([k, f]) => `<option value="${k}" ${k === e.fonte ? 'selected' : ''}>${f.nome}</option>`).join('')}</select></label>
    <label class="campo"><span id="eRot"></span><input type="text" inputmode="decimal" id="eVal"></label>
    <p class="sec peq" id="eEx" style="margin:-6px 0 10px"></p>
    <label class="campo" id="eUsoBox"><span>Tempo com a resistência ou chama ligada (%)</span><input type="text" inputmode="decimal" id="eUso" value="${numBR(e.usoPct || 100)}"></label>
    <p class="sec peq" id="eUsoEx" style="margin:-6px 0 10px">Fornos ligam e desligam pelo termostato depois de aquecer. Na dúvida, deixe 100%.</p>
    <div class="bloco" style="background:var(--papel)"><span id="eCusto" class="sec">Preencha para ver o custo por hora.</span></div>
    <div class="rodape">${eq ? '<button class="btn perigo" id="eExc">Excluir</button>' : ''}<button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="eOk">Salvar</button></div>`,
    (m, fechar) => {
      const montar = () => {
        const f = $('#eFonte', m).value, campo = FONTES[f].campo;
        const r = { ...e, nome: $('#eNome', m).value.trim(), fonte: f, usoPct: lerNum($('#eUso', m).value) || 100 };
        ['potenciaW', 'consumo', 'custoHora'].forEach(c => { if (c !== campo) delete r[c]; });
        r[campo] = lerNum($('#eVal', m).value);
        return r;
      };
      const atualizar = debounce(async () => {
        const r = montar();
        try {
          const c = await GC.chamar('custoEquipamento', { equipamento: r });
          $('#eCusto', m).innerHTML = c.custoHora != null ? `<b>${brl(c.custoHora)} por hora</b>, ${brl(c.custoHora / 2)} em 30 minutos` : esc(c.aviso || '');
        } catch (x) { }
      }, 200);
      const trocar = () => {
        const f = FONTES[$('#eFonte', m).value];
        $('#eRot', m).textContent = f.rot; $('#eEx', m).textContent = f.ex;
        $('#eVal', m).value = numBR(e[f.campo], 4);
        $('#eUsoBox', m).hidden = $('#eUsoEx', m).hidden = $('#eFonte', m).value === 'hora';
        atualizar();
      };
      $('#eFonte', m).onchange = trocar;
      ['#eVal', '#eUso'].forEach(s => $(s, m).oninput = atualizar);
      trocar();
      $('#eOk', m).onclick = async () => {
        const r = montar();
        if (!r.nome) return toast('Dê um nome ao equipamento.', true);
        if (!(r[FONTES[r.fonte].campo] > 0)) return toast('Informe ' + FONTES[r.fonte].rot.toLowerCase() + '.', true);
        r.categoria = { eletrico: 'Energia elétrica', glp: 'Gás', encanado: 'Gás' }[r.fonte] || 'Outros';
        try { const s = await salvar('equipamentos', r); fechar(); toast('Equipamento salvo.'); if (aoSalvar) aoSalvar(porId('equipamentos', s.id) || s); else render(); } catch (x) { falha(x); }
      };
      if (eq) $('#eExc', m).onclick = async () => {
        const usado = S.dados.receitas.filter(rc => (rc.indiretos || []).some(g => g.equipamentoId === eq.id)).length;
        fechar();
        if (!await confirmar('Excluir equipamento?', usado ? `Ele é usado em ${usado} receita(s), que ficarão com aviso.` : eq.nome, 'Excluir', true)) return;
        await excluir('equipamentos', eq.id); render();
      };
    });
}

/* ---------- gastos salvos ---------- */
ACOES.novoModelo = () => editarModelo(null);
ACOES.editarModelo = el => editarModelo(porId('gastosIndiretos', el.dataset.id));
function editarModelo(md) {
  const g = md ? { ...md } : { nome: '', categoria: CAT_INDIRETAS()[0], modo: 'manual' };
  modal(`<h2>${md ? 'Editar gasto salvo' : 'Novo gasto salvo'}</h2>
    <label class="campo"><span>Nome</span><input type="text" id="gNome" value="${esc(g.nome)}" placeholder="Ex.: Forno 40 min, Água e detergente"></label>
    <label class="campo"><span>Categoria</span><select id="gCat">${opcoesCat(g.categoria)}</select></label>
    <div class="segmento" style="margin-bottom:12px"><button data-m="manual" class="${g.modo !== 'equipamento' ? 'on' : ''}">Valor fixo</button><button data-m="equipamento" class="${g.modo === 'equipamento' ? 'on' : ''}">Equipamento por tempo</button></div>
    <div id="gManual"><label class="campo"><span>Valor por receita (R$)</span><input type="text" inputmode="decimal" id="gVal" value="${g.valor != null ? numBR(g.valor, 2) : ''}"></label></div>
    <div id="gEquip" class="linha-campos">${S.dados.equipamentos.length
      ? `<label class="campo"><span>Equipamento</span><select id="gEq">${opcoesEquip(g.equipamentoId)}</select></label>
         <label class="campo"><span>Minutos ligado</span><input type="text" inputmode="decimal" id="gMin" value="${numBR(g.minutos || 30)}"></label>`
      : '<p class="sec">Cadastre um equipamento primeiro.</p>'}</div>
    <div class="rodape">${md ? '<button class="btn perigo" id="gExc">Excluir</button>' : ''}<button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="gOk">Salvar</button></div>`,
    (m, fechar) => {
      const mostrar = () => { $('#gManual', m).hidden = g.modo === 'equipamento'; $('#gEquip', m).hidden = g.modo !== 'equipamento'; };
      $$('.segmento button', m).forEach(b => b.onclick = () => { g.modo = b.dataset.m; $$('.segmento button', m).forEach(x => x.classList.toggle('on', x === b)); mostrar(); });
      mostrar();
      $('#gOk', m).onclick = async () => {
        const r = { ...g, nome: $('#gNome', m).value.trim(), categoria: $('#gCat', m).value };
        if (!r.nome) return toast('Dê um nome.', true);
        if (r.modo === 'equipamento') {
          if (!$('#gEq', m)) return toast('Cadastre um equipamento primeiro.', true);
          r.equipamentoId = $('#gEq', m).value; r.minutos = lerNum($('#gMin', m).value) || 0; r.valor = null;
        } else { r.valor = lerNum($('#gVal', m).value); r.equipamentoId = null; r.minutos = null; }
        try { await salvar('gastosIndiretos', r); fechar(); toast('Gasto salvo.'); render(); } catch (x) { falha(x); }
      };
      if (md) $('#gExc', m).onclick = async () => { fechar(); await excluir('gastosIndiretos', md.id); render(); };
    });
}

/* ---------- categorias ---------- */
ACOES.addCatInd = async () => {
  const n = $('#novaCatInd').value.trim(); if (!n) return;
  const cats = CAT_INDIRETAS(); if (cats.includes(n)) return;
  try { await salvarTarifas({ categoriasIndiretas: [...cats, n] }); render(); } catch (e) { falha(e); }
};
ACOES.remCatInd = async el => {
  const c = el.dataset.c;
  const usado = S.dados.gastosIndiretos.some(m => m.categoria === c);
  if (usado) return toast('Há gastos salvos nesta categoria. Mude a categoria deles antes de remover.', true);
  try { await salvarTarifas({ categoriasIndiretas: CAT_INDIRETAS().filter(x => x !== c) }); render(); } catch (e) { falha(e); }
};
