/* Vendas, configurações, Excel/backup e pareamento celular ↔ computador. */
'use strict';

/* ---------------- vendas ---------------- */
TELAS.vendas = () => {
  const vs = S.dados.vendas;
  const grupos = {};
  vs.forEach(v => { const m = (v.data || '').slice(0, 7); (grupos[m] = grupos[m] || []).push(v); });
  return `<div class="cabeca"><div><h1>Vendas</h1><p class="sec">O faturamento entra no resumo para calcular lucro e margem.</p></div>
    <div class="acoes"><button class="btn fr" data-acao="novaVenda">${ICONES.mais} Registrar venda</button></div></div>
  <div class="bloco">${vs.length ? Object.keys(grupos).sort().reverse().map(m => `
    <div class="mes-sep"><span>${mesLongo(m)}</span><span>${brl(grupos[m].reduce((s, v) => s + (v.valorTotal || 0), 0))}</span></div>
    <ul class="lista">${grupos[m].map(v => `<li data-acao="editarVenda" data-id="${v.id}">
      <span class="icone-orig venda">${ICONES.mais}</span>
      <div class="meio"><div class="titulo">${esc(v.descricao || 'Venda')}</div>
        <div class="sec peq">${dataBR(v.data)}${v.quantidade > 1 ? `, ${numBR(v.quantidade)} × ${brl(v.valorUnitario)}` : ''}${v.pagamento ? ', ' + esc(v.pagamento) : ''}${v.cliente ? ', ' + esc(v.cliente) : ''}</div></div>
      <div class="valor" style="color:var(--framboesa)">${brl(v.valorTotal)}</div></li>`).join('')}</ul>`).join('')
    : `<div class="vazio"><p>Nenhuma venda registrada.</p><p class="sec">Registre encomendas e vendas para o resumo mostrar lucro, margem e custo de insumos sobre as vendas.</p>
       <button class="btn fr" data-acao="novaVenda">${ICONES.mais} Registrar venda</button></div>`}</div>`;
};
ACOES.novaVenda = () => formVenda(null);
ACOES.editarVenda = el => formVenda(porId('vendas', el.dataset.id));

function formVenda(v) {
  const rs = S.dados.receitas;
  modal(`<h2>${v ? 'Editar venda' : 'Registrar venda'}</h2>
    <label class="campo"><span>Receita (opcional, sugere o preço)</span><select id="vRec"><option value="">Nenhuma</option>
      ${rs.map(r => `<option value="${r.id}" ${v && v.receitaId === r.id ? 'selected' : ''}>${esc(r.nome)} (${brl(r.calculo.precoVenda)})</option>`).join('')}</select></label>
    <label class="campo"><span>Descrição</span><input type="text" id="vDesc" value="${esc(v ? v.descricao : '')}" placeholder="Ex.: Bolo de aniversário 2 kg"></label>
    <div class="linha-campos">
      <label class="campo"><span>Data</span><input type="date" id="vData" value="${v ? v.data : hoje()}"></label>
      <label class="campo"><span>Quantidade</span><input type="text" inputmode="decimal" id="vQtd" value="${numBR(v ? v.quantidade : 1)}"></label>
      <label class="campo"><span>Valor unitário (R$)</span><input type="text" inputmode="decimal" id="vVal" value="${v ? numBR(v.valorUnitario, 2) : ''}"></label>
    </div>
    <div class="linha-campos">
      <label class="campo"><span>Pagamento</span><select id="vPag"><option value="">Não informado</option>${PAGAMENTOS.map(p => `<option ${v && v.pagamento === p ? 'selected' : ''}>${p}</option>`).join('')}</select></label>
      <label class="campo"><span>Cliente (opcional)</span><input type="text" id="vCli" value="${esc(v ? v.cliente || '' : '')}"></label>
    </div>
    <p class="sec" id="vTot"></p>
    <div class="rodape">${v ? '<button class="btn perigo" id="vExc">Excluir</button>' : ''}<button class="btn" data-fechar>Cancelar</button><button class="btn fr" id="vOk">Salvar venda</button></div>`,
    (el, fechar) => {
      const tot = () => { const t = (lerNum($('#vQtd', el).value) || 0) * (lerNum($('#vVal', el).value) || 0); $('#vTot', el).textContent = 'Total ' + brl(t); };
      $('#vRec', el).onchange = () => {
        const r = porId('receitas', $('#vRec', el).value); if (!r) return;
        if (!$('#vDesc', el).value) $('#vDesc', el).value = r.nome;
        $('#vVal', el).value = numBR(Math.round(r.calculo.precoVenda * 100) / 100, 2); tot();
      };
      ['#vQtd', '#vVal'].forEach(s => $(s, el).oninput = tot); tot();
      if (v) $('#vExc', el).onclick = async () => { fechar(); await excluir('vendas', v.id); toast('Venda excluída.'); render(); };
      $('#vOk', el).onclick = async () => {
        const q = lerNum($('#vQtd', el).value) || 1, vu = lerNum($('#vVal', el).value);
        if (vu == null) return toast('Informe o valor.', true);
        const rec = { ...(v || {}), receitaId: $('#vRec', el).value, descricao: $('#vDesc', el).value.trim() || 'Venda', data: $('#vData', el).value || hoje(),
          quantidade: q, valorUnitario: vu, valorTotal: Math.round(q * vu * 100) / 100, pagamento: $('#vPag', el).value, cliente: $('#vCli', el).value.trim() };
        try { await salvar('vendas', rec); fechar(); toast('Venda salva.'); render(); } catch (e) { falha(e); }
      };
    });
}

/* ---------------- mais ---------------- */
TELAS.mais = () => {
  const c = S.dados.config || {};
  const cats = c.categorias || [];
  const excl = new Set(c.categoriasExcluidas || []);
  return `<div class="cabeca"><h1>Mais opções</h1></div>
  <div class="grade g2">
    <section class="bloco"><h2>Planilha do Excel</h2>
      <p class="sec" style="margin-bottom:10px">No mesmo formato da sua planilha de controle: Análise Resumo com gráficos, Resumo Mês (tabela dinâmica), Notas_Consolidadas, Preço Médio, Conciliação Bancária, Insumos, Receitas e Vendas.</p>
      <div class="chips"><button class="btn pri" data-acao="exportarTudo">${ICONES.baixar} Baixar Excel completo</button>
      <button class="btn" data-acao="importarPlanilha">${ICONES.planilha} Importar planilha</button></div>
    </section>
    <section class="bloco"><h2>Aparelhos</h2>
      <p class="sec" style="margin-bottom:10px">Use o app em vários celulares e no computador com os mesmos dados, sincronizados pelo Wi-Fi.</p>
      <button class="btn" data-acao="ir" data-para="dispositivos">Configurar aparelhos</button>
    </section>
    <section class="bloco"><h2>Seu negócio</h2>
      <label class="campo"><span>Nome que aparece no topo e no Excel</span><input type="text" id="cNome" value="${esc(c.nomeNegocio || '')}" placeholder="Ex.: Sol & Flor Confeitaria"></label>
      <button class="btn pri" data-acao="salvarNome">Salvar nome</button>
    </section>
    <section class="bloco"><h2>Categorias</h2>
      <p class="sec" style="margin-bottom:8px">Desmarque as que não são do negócio (ex.: compras pessoais misturadas na mesma nota). Elas ficam fora do resumo.</p>
      ${cats.map(k => `<label style="display:flex;gap:8px;align-items:center;padding:4px 0"><input type="checkbox" class="cInc" value="${esc(k)}" ${excl.has(k) ? '' : 'checked'}> ${esc(k)}</label>`).join('')}
      <div style="display:flex;gap:8px;margin-top:8px"><input type="text" id="cNova" placeholder="Nova categoria"><button class="btn" data-acao="novaCategoria">Adicionar</button></div>
    </section>
    <section class="bloco"><h2>Cópia de segurança</h2>
      <p class="sec" style="margin-bottom:10px">Um arquivo com todas as notas, receitas, insumos e vendas. Restaurar junta os dados, sem apagar nada.</p>
      <div class="chips"><button class="btn" data-acao="backup">${ICONES.baixar} Salvar cópia</button><button class="btn" data-acao="restaurar">Restaurar cópia</button></div>
    </section>
  </div>`;
};
TELAS.mais.depois = el => $$('.cInc', el).forEach(ch => ch.onchange = async () => {
  const c = S.dados.config;
  const excl = new Set(c.categoriasExcluidas || []);
  if (ch.checked) excl.delete(ch.value); else excl.add(ch.value);
  try { await salvar('config', { ...c, categoriasExcluidas: [...excl] }); toast('Categorias atualizadas.'); } catch (e) { falha(e); }
});
ACOES.exportarTudo = () => baixarExcel({ de: primeiroMesComDados(), ate: ultimoMesComDados() });
ACOES.salvarNome = async () => { try { await salvar('config', { ...S.dados.config, nomeNegocio: $('#cNome').value.trim() }); toast('Nome salvo.'); } catch (e) { falha(e); } };
ACOES.novaCategoria = async () => {
  const n = $('#cNova').value.trim(); if (!n) return;
  const c = S.dados.config; if ((c.categorias || []).includes(n)) return;
  await salvar('config', { ...c, categorias: [...(c.categorias || []), n] }); render();
};
ACOES.backup = async () => {
  try {
    if (ehDesktop) { location.href = '/baixar/backup?t=' + encodeURIComponent(window.GC_TOKEN); return; }
    const b = await GC.chamar('backup');
    if (ehAndroid) await GC.chamar('nativo.salvarArquivo', { nome: b.nome, texto: b.json, mime: 'application/json' });
    else { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([b.json], { type: 'application/json' })); a.download = b.nome; a.click(); }
  } catch (e) { falha(e); }
};
ACOES.restaurar = async () => {
  try {
    const f = await escolherArquivo('.json,application/json', 'texto');
    const r = await GC.chamar('restaurarBackup', { json: f.conteudo });
    await recarregar(); toast(`${r.aplicados} registros restaurados.`); render();
  } catch (e) { falha(e); }
};

/* ---------------- aparelhos ---------------- */
TELAS.dispositivos = async () => {
  const d = await GC.chamar('dispositivos');
  const pares = d.pares || [];
  const conecto = p => p.conecto || (p.conecto == null && p.tipo === 'computador');
  return `<div class="cabeca"><div><h1>Aparelhos</h1><p class="sec">Vários celulares e computadores podem usar os mesmos dados. Os aparelhos pareados se lembram um do outro e sincronizam sozinhos quando estão no mesmo Wi-Fi.</p></div></div>
  <div class="grade g2">
    <section class="bloco"><h2>Este aparelho</h2>
      <label class="campo"><span>Nome</span><input type="text" id="dNome" value="${esc(d.nome)}"></label>
      <button class="btn" data-acao="renomear">Salvar nome</button>
    </section>
    <section class="bloco"><header><h2>Aparelhos pareados</h2>${pares.some(conecto) ? `<button class="btn pri peq" data-acao="sincronizar">Sincronizar agora</button>` : ''}</header>
      ${pares.length ? `<ul class="lista">${pares.map(p => `<li style="cursor:default"><div class="meio"><div class="titulo">${esc(p.nome)}</div>
        <div class="sec peq">${p.tipo === 'computador' ? 'computador' : 'celular'}, ${p.ultimoSync ? 'sincronizado ' + tempoRelativo(p.ultimoSync) : 'ainda não sincronizado'}</div></div>
        <button class="btn lev peq perigo" data-acao="esquecer" data-id="${p.id}" data-nome="${esc(p.nome)}">Esquecer</button></li>`).join('')}</ul>`
      : '<p class="sec">Nenhum aparelho pareado.</p>'}
    </section>
  </div>
  <section class="bloco" style="margin-top:14px"><h2>${ehAndroid ? 'Parear com outro aparelho' : 'Parear celulares'}</h2>
    ${ehAndroid ? `
      <p class="sec" style="margin-bottom:10px">Abra o Gestão Confeitaria no computador ou no outro celular, no mesmo Wi-Fi, e procure. O código de 6 dígitos aparece no outro aparelho.</p>
      <p class="sec peq" style="margin-bottom:10px">Com um computador sempre ligado, pareie cada celular com ele: todos ficam com os mesmos dados. Sem computador, pareie os celulares entre si (eles sincronizam quando os dois estão com o app aberto).</p>
      <div class="chips"><button class="btn pri" data-acao="procurar">Procurar aparelhos</button><button class="btn" data-acao="ipManual">Digitar o endereço</button></div>
      <ul class="lista" id="achados" style="margin-top:10px"></ul>`
    : `<ol class="sec" style="padding-left:18px;margin:0;line-height:1.8">
        <li>Deixe este app aberto e o computador no mesmo Wi-Fi do celular.</li>
        <li>No celular, abra <b>Mais &gt; Aparelhos &gt; Procurar aparelhos</b> e escolha este computador.</li>
        <li>Um código de 6 dígitos aparece aqui. Digite-o no celular para confirmar.</li>
        <li>Repita em cada celular: todos passam a compartilhar os mesmos dados por este computador.</li></ol>
       <p class="sec peq" style="margin-top:10px">Se o Windows perguntar, permita o acesso do Java em redes privadas.</p>`}
  </section>`;
};
ACOES.renomear = async () => { await GC.chamar('renomearDispositivo', { nome: $('#dNome').value }); toast('Nome salvo.'); };
ACOES.esquecer = async el => {
  if (!await confirmar('Esquecer aparelho?', `${el.dataset.nome} deixa de sincronizar. Para voltar, será preciso parear de novo.`, 'Esquecer', true)) return;
  await GC.chamar('esquecerDispositivo', { id: el.dataset.id }); render(); atualizarPill();
};
ACOES.sincronizar = async el => {
  el.disabled = true; el.innerHTML = '<span class="carregando"></span> Sincronizando';
  try {
    const r = await GC.chamar('sincronizar');
    const t = r.reduce((a, x) => ({ e: a.e + x.enviados, r: a.r + x.recebidos }), { e: 0, r: 0 });
    const fora = r.filter(x => x.erro).map(x => x.computador);
    await recarregar(); toast(`Sincronizado: ${t.e} enviados, ${t.r} recebidos.` + (fora.length ? ` Fora do ar: ${fora.join(', ')}.` : '')); render(); atualizarPill();
  } catch (e) { falha(e); render(); }
};
ACOES.procurar = async el => {
  const box = $('#achados');
  el.disabled = true; box.innerHTML = '<li class="sec" style="cursor:default"><span class="carregando"></span> Procurando na rede…</li>';
  try {
    const lista = await GC.chamar('descobrir');
    box.innerHTML = lista.length ? lista.map(c => `<li style="cursor:default"><div class="meio"><div class="titulo">${esc(c.nome)}</div><div class="sec peq">${c.tipo === 'celular' ? 'celular' : 'computador'}, ${esc(c.host)}</div></div>
      ${c.pareado ? '<span class="selo ok">pareado</span>' : `<button class="btn pri peq" data-acao="parear" data-host="${esc(c.host)}" data-porta="${c.porta}" data-nome="${esc(c.nome)}">Parear</button>`}</li>`).join('')
      : '<li class="sec" style="cursor:default">Nenhum aparelho encontrado. Confira se o app está aberto no outro aparelho e se os dois estão no mesmo Wi-Fi, ou digite o endereço.</li>';
  } catch (e) { box.innerHTML = ''; falha(e); }
  el.disabled = false;
};
ACOES.ipManual = () => modal(`<h2>Endereço do computador</h2>
  <p class="sec">No computador, aparece na janelinha do app (ex.: 192.168.0.12). No celular, em Configurações &gt; Wi-Fi &gt; detalhes da rede.</p>
  <label class="campo"><span>Endereço IP</span><input type="text" id="mIp" inputmode="decimal" placeholder="192.168.0.12"></label>
  <div class="rodape"><button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="mOk">Parear</button></div>`,
  (el, fechar) => $('#mOk', el).onclick = () => { const h = $('#mIp', el).value.trim(); if (!h) return; fechar(); parear(h, 47822, 'o aparelho'); });
ACOES.parear = el => parear(el.dataset.host, +el.dataset.porta, el.dataset.nome);

async function parear(host, porta, nome) {
  const espera = modal(`<h2>Conectando…</h2><p class="sec"><span class="carregando"></span> Falando com ${esc(nome)}</p>`);
  try {
    const r = await GC.chamar('parearIniciar', { host, porta });
    espera();
    modal(`<h2>Digite o código</h2>
      <p class="sec">Um código de 6 dígitos apareceu na tela de <b>${esc(r.computador)}</b>. Digite-o aqui.</p>
      <input type="text" id="mCod" inputmode="numeric" maxlength="7" autocomplete="one-time-code" style="font-size:28px;text-align:center;letter-spacing:.2em;margin-top:12px">
      <div class="rodape"><button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="mOk">Confirmar</button></div>`,
      (el, fechar) => {
        $('#mCod', el).focus();
        $('#mOk', el).onclick = async () => {
          try {
            await GC.chamar('parearConfirmar', { codigo: $('#mCod', el).value });
            fechar(); toast('Pareado! Sincronizando…');
            await GC.chamar('sincronizar', { id: r.id }); await recarregar(); toast('Pronto: aparelhos sincronizados.'); render(); atualizarPill();
          } catch (e) { fechar(); falha(e); }
        };
      });
  } catch (e) { espera(); falha(e); }
}
