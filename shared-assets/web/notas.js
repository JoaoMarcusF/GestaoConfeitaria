/* Notas: lista, detalhe, lançamento manual e todas as formas de importar. */
'use strict';

const PAGAMENTOS = ['Cartão de Crédito', 'Cartão de Débito', 'Pix', 'Dinheiro', 'Transferência/Carteira Digital', 'Boleto', 'Vale', 'Outros'];
const UNIDADES_NOTA = ['UN', 'KG', 'G', 'L', 'ML', 'DZ', 'CX', 'PCT'];
const categorias = () => (S.dados.config && S.dados.config.categorias) || ['Insumos'];
const ORIGENS = { sefaz: 'QR code', xml: 'XML', planilha: 'planilha', manual: 'manual' };

/* ---------------- lista ---------------- */
TELAS.notas = r => {
  const q = r.params.busca || '', loja = r.params.loja || '', pend = r.params.pend === '1';
  const nq = norm(q);
  const notas = S.dados.notas.filter(n =>
    (!loja || norm(n.emitente) === norm(loja)) && (!pend || !n.conferido) &&
    (!nq || norm(n.emitente).includes(nq) || (n.itens || []).some(i => norm(i.descricao).includes(nq))));
  const grupos = {};
  notas.forEach(n => { const m = (n.emissao || '').slice(0, 7); (grupos[m] = grupos[m] || []).push(n); });
  const meses = Object.keys(grupos).sort().reverse();
  return `
  <div class="cabeca">
    <div><h1>Notas</h1><p class="sec">${notas.length} de ${S.dados.notas.length} notas</p></div>
    <div class="acoes"><button class="btn pri" data-acao="ir" data-para="adicionar">${ICONES.mais} Adicionar nota</button></div>
  </div>
  <div class="filtros">
    <input type="search" id="busca" placeholder="Buscar loja ou produto" value="${esc(q)}" style="flex:1;min-width:200px">
    ${loja ? `<button class="chip on x" data-acao="limparLoja">${esc(loja)}</button>` : ''}
    <button class="chip ${pend ? 'on' : ''}" data-acao="soPendentes">Só não conferidas</button>
  </div>
  <div class="bloco">
    ${notas.length ? meses.map(m => `
      <div class="mes-sep"><span>${mesLongo(m)}</span><span>${brl(grupos[m].reduce((s, n) => s + (n.valorTotal || 0), 0))}</span></div>
      <ul class="lista">${grupos[m].map(linhaNota).join('')}</ul>`).join('')
      : `<div class="vazio"><p>${S.dados.notas.length ? 'Nenhuma nota encontrada com esse filtro.' : 'Nenhuma nota ainda.'}</p>
         <button class="btn pri" data-acao="ir" data-para="adicionar">${ICONES.mais} Adicionar nota</button></div>`}
  </div>`;
};
TELAS.notas.depois = (el, r) => {
  const b = $('#busca', el);
  b.oninput = debounce(() => {
    const p = new URLSearchParams(r.params); if (b.value) p.set('busca', b.value); else p.delete('busca');
    history.replaceState(null, '', '#/notas?' + p.toString());
    const pos = b.selectionStart; render().then(() => { const n = $('#busca'); n.focus(); n.setSelectionRange(pos, pos); });
  }, 300);
};
function linhaNota(n) {
  const manual = n.origem === 'manual' || n.origem === 'planilha';
  return `<li data-acao="ir" data-para="nota/${n.id}">
    <span class="icone-orig ${manual ? 'manual' : ''}">${n.origem === 'sefaz' ? ICONES.qr : n.origem === 'xml' ? ICONES.xml : manual ? ICONES.lapis : ICONES.nota}</span>
    <div class="meio"><div class="titulo">${esc(n.emitente || 'Sem loja')}</div>
      <div class="sec peq">${dataBR(n.emissao)}, ${(n.itens || []).length} ${(n.itens || []).length === 1 ? 'item' : 'itens'}${n.pagamento ? ', ' + esc(n.pagamento) : ''}</div></div>
    ${n.conferido ? `<span class="selo ok" title="Conferido no extrato">conferida</span>` : ''}
    <div class="valor">${brl(n.valorTotal)}</div></li>`;
}
function filtrarNotas(campo, valor) {
  const r = rotaAtual(); const p = new URLSearchParams(r.params);
  if (valor) p.set(campo, valor); else p.delete(campo);
  ir('notas' + (p.toString() ? '?' + p : ''));
}
ACOES.limparLoja = () => filtrarNotas('loja', '');
ACOES.soPendentes = () => filtrarNotas('pend', rotaAtual().params.pend === '1' ? '' : '1');

/* ---------------- adicionar ---------------- */
TELAS.adicionar = () => `
  <div class="cabeca"><div><h1>Adicionar nota</h1><p class="sec">Escolha como trazer a compra para o app.</p></div></div>
  <div class="bloco"><ul class="opcoes">
    ${ehAndroid ? `
    <li><button data-acao="escanear"><span class="icone-orig">${ICONES.qr}</span><div><b>Escanear o QR code</b><span>Aponte a câmera para o QR no rodapé do cupom</span></div></button></li>
    <li><button data-acao="fotoQr"><span class="icone-orig">${ICONES.foto}</span><div><b>Ler o QR de uma foto</b><span>Escolha uma foto do cupom na galeria</span></div></button></li>` : ''}
    <li><button data-acao="colarLink"><span class="icone-orig">${ICONES.link}</span><div><b>Colar o link do QR code</b><span>O endereço da consulta na SEFAZ</span></div></button></li>
    <li><button data-acao="chaveAcesso"><span class="icone-orig">${ICONES.chave}</span><div><b>Digitar a chave de acesso</b><span>Para cupons com QR apagado: os 44 números impressos no cupom</span></div></button></li>
    <li><button data-acao="importarXml"><span class="icone-orig">${ICONES.xml}</span><div><b>Importar o XML da nota</b><span>Arquivo .xml enviado pelo fornecedor ou baixado do portal</span></div></button></li>
    <li><button data-acao="ir" data-para="nota/nova"><span class="icone-orig manual">${ICONES.lapis}</span><div><b>Lançar manualmente</b><span>Feira, compra sem nota, curso, entrega…</span></div></button></li>
    <li><button data-acao="importarPlanilha"><span class="icone-orig manual">${ICONES.planilha}</span><div><b>Importar planilha</b><span>Traga o histórico da aba Notas_Consolidadas (.xlsx/.xlsm)</span></div></button></li>
  </ul>
  ${ehDesktop ? '<p class="sec peq" style="margin-top:12px">Para escanear o QR code, use o app no celular: as notas chegam aqui pela sincronização.</p>' : ''}
  </div>`;

ACOES.escanear = async () => { try { const t = await GC.chamar('nativo.escanear'); if (t) await consultarLink(t); } catch (e) { falha(e); } };
ACOES.fotoQr = async () => { try { const t = await GC.chamar('nativo.fotoQr'); if (t) await consultarLink(t); } catch (e) { falha(e); } };
ACOES.colarLink = () => modal(`<h2>Link do QR code</h2>
  <p class="sec">Cole o endereço que aparece ao ler o QR com outro app (começa com http e tem .gov.br).</p>
  <label class="campo"><span>Link</span><input type="text" id="mLink" placeholder="https://…fazenda…gov.br/…?p=…"></label>
  <div class="rodape"><button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="mOk">Consultar</button></div>`,
  (el, fechar) => { $('#mOk', el).onclick = () => { const v = $('#mLink', el).value.trim(); if (!v) return; fechar(); consultarLink(v); }; });

/** Busca a página da SEFAZ e extrai os itens. No celular, se falhar, abre a página (captcha, páginas com JavaScript). */
async function consultarLink(texto) {
  texto = String(texto || '').trim();
  const fechar = modal(`<h2>Consultando a SEFAZ</h2><p class="sec"><span class="carregando"></span> Buscando os itens da nota…</p>`);
  const ch = (texto.replace(/\s/g, '').match(/(?<!\d)\d{44}(?!\d)/) || [])[0] || '';
  try {
    if (!/^https?:\/\//i.test(texto)) throw new Error('O QR code não contém um link de consulta. Use a opção "Digitar a chave de acesso".');
    let dados = null;
    try {
      const pg = await GC.chamar('buscarPagina', { url: texto });
      const doc = new DOMParser().parseFromString(pg.html, 'text/html');
      const r = GC_EXTRATOR.extrair(doc);
      if (r.itens.length) dados = r;
    } catch (e) { if (!ehAndroid) throw e; }
    if (!dados && ehAndroid) { fechar(); dados = await GC.chamar('nativo.consultarPagina', { url: texto, chave: ch }); }
    if (!dados) throw new Error('A página da SEFAZ não trouxe os itens (pode ter pedido verificação). No computador, importe o XML ou lance a nota manualmente; no celular, o app abre a página para você resolver.');
    const r = await GC.chamar('importarSefaz', { dados, url: texto, chave: ch });
    fechar(); await recarregar();
    toast(r.existia ? 'Nota atualizada.' : `Nota importada: ${r.nota.itens.length} itens, ${brl(r.nota.valorTotal)}.`);
    ir('nota/' + r.nota.id);
  } catch (e) { fechar(); if (e.message !== 'cancelado') falha(e); }
}

ACOES.chaveAcesso = () => modal(`<h2>Chave de acesso</h2>
  <p class="sec">São os 44 números impressos no cupom, em grupos de 4, perto do QR code.</p>
  <label class="campo"><span>Chave</span><input type="text" id="mCh" inputmode="numeric" placeholder="3326 1012 3456 …"></label>
  <div id="mInfo"></div>
  <div class="rodape"><button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="mOk">Continuar</button></div>`,
  (el, fechar) => {
    $('#mOk', el).onclick = async () => {
      try {
        const info = await GC.chamar('chaveInfo', { chave: $('#mCh', el).value });
        const bloco = $('#mInfo', el);
        bloco.innerHTML = `<div class="bloco" style="background:var(--papel)">
          <p><b>${info.modelo === '65' ? 'NFC-e (cupom)' : 'NF-e'}</b> nº ${esc(info.numero)}, série ${esc(info.serie)}, ${rotMes(info.anoMes)}</p>
          <p class="sec">Emitente CNPJ ${esc(info.cnpj)}, ${esc(info.uf)}</p>
          ${info.valida ? '' : '<p class="aviso-caixa" style="margin-top:8px">O dígito verificador não confere. Confira se digitou todos os números certos.</p>'}
          ${info.existe ? '<p class="aviso-caixa" style="margin-top:8px">Esta nota já está cadastrada.</p>' : ''}
          <label class="campo" style="margin-top:10px"><span>Site de consulta da SEFAZ ${esc(info.uf)} (impresso no cupom)</span><input type="text" id="mUrl" value="${esc(info.urlConsulta || '')}"></label>
        </div>`;
        $('.rodape', el).innerHTML = `<button class="btn" id="mManual">Lançar manualmente</button>
          <button class="btn pri" id="mSite">${ehAndroid ? 'Abrir a consulta' : 'Abrir o site da SEFAZ'}</button>`;
        $('#mManual', el).onclick = () => { fechar(); S.rascunhoNota = { chave: info.chave, cnpj: info.cnpj, numero: info.numero, serie: info.serie, emissao: info.anoMes + '-01T00:00:00', origem: 'manual', itens: [] }; ir('nota/nova'); };
        $('#mSite', el).onclick = async () => {
          const url = $('#mUrl', el).value.trim();
          if (!ehAndroid) { window.open(url, '_blank', 'noopener'); toast('Consulte a nota no site e baixe o XML para importar aqui, ou lance manualmente.'); return; }
          fechar();
          try {
            const dados = await GC.chamar('nativo.consultarPagina', { url, chave: info.chave, modoChave: true });
            const r = await GC.chamar('importarSefaz', { dados, url, chave: info.chave });
            await recarregar(); toast(`Nota importada: ${r.nota.itens.length} itens.`); ir('nota/' + r.nota.id);
          } catch (e) { if (e.message !== 'cancelado') falha(e); }
        };
      } catch (e) { falha(e); }
    };
  });

ACOES.importarXml = async () => {
  try {
    const f = await escolherArquivo('.xml,text/xml,application/xml', 'texto');
    const r = await GC.chamar('importarXml', { xml: f.conteudo });
    await recarregar(); toast(r.existia ? 'Nota atualizada com os dados do XML.' : `XML importado: ${r.nota.itens.length} itens.`);
    ir('nota/' + r.nota.id);
  } catch (e) { falha(e); }
};

ACOES.importarPlanilha = async () => {
  try {
    const f = await escolherArquivo('.xlsx,.xlsm,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'base64');
    const fechar = modal(`<h2>Importando ${esc(f.nome)}</h2><p class="sec"><span class="carregando"></span> Lendo as notas…</p>`);
    try {
      const r = await GC.chamar('importarPlanilha', { base64: f.conteudo });
      fechar(); await recarregar();
      toast(`${r.notas} notas e ${r.itens} itens lidos (${r.novas} novas).`);
      ir('resumo');
    } catch (e) { fechar(); throw e; }
  } catch (e) { falha(e); }
};

/* ---------------- detalhe ---------------- */
TELAS.nota = r => {
  if (r.id === 'nova' || r.sub === 'editar') return formNota(r.id === 'nova' ? null : porId('notas', r.id));
  const n = porId('notas', r.id);
  if (!n) return '<div class="vazio">Nota não encontrada.</div>';
  const itens = n.itens || [];
  const bruto = itens.reduce((s, i) => s + (i.valorTotal || 0), 0);
  return `
  <div class="cabeca">
    <div><h1>${esc(n.emitente || 'Nota')}</h1>
      <p class="sec">${dataBR(n.emissao)}${n.emissao && n.emissao.slice(11, 16) !== '00:00' ? ' às ' + n.emissao.slice(11, 16) : ''}${n.numero ? ', nota nº ' + esc(n.numero) : ''}, importada por ${ORIGENS[n.origem] || n.origem}</p></div>
    <div class="acoes">
      <button class="btn" data-acao="ir" data-para="nota/${n.id}/editar">${ICONES.lapis} Editar</button>
      <button class="btn perigo" data-acao="excluirNota" data-id="${n.id}">Excluir</button>
    </div>
  </div>
  <div class="grade g2">
    <section class="bloco">
      <table class="conta">
        <tr><td>Itens (${itens.length})</td><td>${brl(bruto)}</td></tr>
        ${n.desconto > 0.004 ? `<tr><td>Descontos</td><td>− ${brl(n.desconto)}</td></tr>` : ''}
        <tr class="total"><td><b>Total pago</b></td><td>${brl(n.valorTotal)}</td></tr>
      </table>
      <p class="sec" style="margin-top:8px">${esc(n.pagamento || 'Forma de pagamento não informada')}</p>
      <label style="display:flex;gap:8px;align-items:center;margin-top:12px;cursor:pointer">
        <input type="checkbox" id="conferido" ${n.conferido ? 'checked' : ''}> Conferida no extrato bancário</label>
    </section>
    <section class="bloco">
      <p><b>${esc(n.emitente || '')}</b></p>
      ${n.cnpj ? `<p class="sec">CNPJ ${esc(n.cnpj)}</p>` : ''}${n.endereco ? `<p class="sec">${esc(n.endereco)}</p>` : ''}
      ${n.chave ? `<p class="sec peq" style="margin-top:8px;word-break:break-all">Chave ${esc(n.chave.replace(/(\d{4})/g, '$1 '))}</p>` : ''}
      ${n.url ? `<p style="margin-top:8px"><a href="${esc(n.url)}" target="_blank" rel="noopener">Ver no site da SEFAZ</a></p>` : ''}
      <label class="campo" style="margin-top:10px"><span>Observações</span><textarea id="obsNota">${esc(n.observacoes || '')}</textarea></label>
    </section>
  </div>
  <section class="bloco" style="margin-top:14px">
    <header><h2>Itens</h2><span class="sec peq">A categoria define onde o gasto entra no resumo</span></header>
    <div class="rolar"><table class="tab">
      <thead><tr><th>Produto</th><th class="n">Qtd.</th><th class="n">Unitário</th><th class="n">Total</th><th>Categoria</th></tr></thead>
      <tbody>${itens.map((i, k) => `<tr>
        <td>${esc(i.descricao)}${i.codigo ? `<div class="sec peq">cód. ${esc(i.codigo)}</div>` : ''}</td>
        <td class="n">${numBR(i.quantidade)} ${esc(i.unidade || '')}</td>
        <td class="n">${brl(i.valorUnitario)}</td>
        <td class="n">${brl(i.valorAjustado ?? i.valorTotal)}${i.valorAjustado != null && Math.abs(i.valorAjustado - i.valorTotal) > 0.004 ? `<div class="sec peq"><s>${brl(i.valorTotal)}</s></div>` : ''}</td>
        <td><select data-item="${k}" class="catItem" style="min-width:150px">${categorias().map(c => `<option ${c === i.categoria ? 'selected' : ''}>${esc(c)}</option>`).join('')}</select></td>
      </tr>`).join('')}</tbody></table></div>
  </section>`;
};
TELAS.nota.depois = (el, r) => {
  const n = porId('notas', r.id); if (!n || r.sub) return TELAS.nota.depoisForm && TELAS.nota.depoisForm(el, r);
  const gravar = async alt => { try { await salvar('notas', { ...porId('notas', n.id), ...alt }); toast('Salvo.'); } catch (e) { falha(e); } };
  $('#conferido', el).onchange = e => gravar({ conferido: e.target.checked });
  $('#obsNota', el).onchange = e => gravar({ observacoes: e.target.value });
  $$('.catItem', el).forEach(s => s.onchange = () => {
    const atual = porId('notas', n.id);
    const itens = atual.itens.map((i, k) => k === +s.dataset.item ? { ...i, categoria: s.value } : i);
    gravar({ itens });
  });
};
ACOES.excluirNota = async el => {
  if (!await confirmar('Excluir nota?', 'A nota sai do resumo e de todos os aparelhos sincronizados.', 'Excluir', true)) return;
  try { await excluir('notas', el.dataset.id); toast('Nota excluída.'); ir('notas'); } catch (e) { falha(e); }
};

/* ---------------- lançamento manual / edição ---------------- */
function formNota(n) {
  const rasc = n ? JSON.parse(JSON.stringify(n)) : (S.rascunhoNota || { origem: 'manual', emissao: hoje() + 'T00:00:00', itens: [] });
  S.rascunhoNota = null;
  if (!rasc.itens.length) rasc.itens.push({ descricao: '', quantidade: 1, unidade: 'UN', valorUnitario: null, categoria: 'Insumos' });
  S.formNota = rasc;
  return `
  <div class="cabeca"><div><h1>${n ? 'Editar nota' : 'Lançar compra'}</h1><p class="sec">${n ? 'Altere os dados e salve.' : 'Para compras sem QR code: feira, internet, serviços.'}</p></div></div>
  <section class="bloco">
    <div class="linha-campos">
      <label class="campo" style="grid-column:span 2"><span>Loja ou fornecedor</span><input type="text" id="nfLoja" value="${esc(rasc.emitente || '')}" list="lojasConhecidas" placeholder="Ex.: Feira de sábado"></label>
      <label class="campo"><span>Data</span><input type="date" id="nfData" value="${(rasc.emissao || '').slice(0, 10)}"></label>
      <label class="campo"><span>Forma de pagamento</span><select id="nfPag"><option value="">Não informado</option>${PAGAMENTOS.map(p => `<option ${p === rasc.pagamento ? 'selected' : ''}>${p}</option>`).join('')}</select></label>
      <label class="campo"><span>CNPJ (opcional)</span><input type="text" id="nfCnpj" value="${esc(rasc.cnpj || '')}"></label>
      <label class="campo"><span>Número da nota (opcional)</span><input type="text" id="nfNum" value="${esc(rasc.numero || '')}"></label>
    </div>
    <datalist id="lojasConhecidas">${[...new Set(S.dados.notas.map(x => x.emitente).filter(Boolean))].slice(0, 300).map(l => `<option value="${esc(l)}">`).join('')}</datalist>
  </section>
  <section class="bloco"><header><h2>Itens</h2><span class="sec peq" id="nfTotal"></span></header>
    <div id="nfItens"></div>
    <button class="btn lev" data-acao="nfAddItem">${ICONES.mais} Adicionar item</button>
    <div class="linha-campos" style="margin-top:10px;max-width:420px">
      <label class="campo"><span>Desconto na nota (R$)</span><input type="text" inputmode="decimal" id="nfDesc" value="${rasc.desconto ? numBR(rasc.desconto, 2) : ''}"></label>
    </div>
  </section>
  <div class="cabeca" style="justify-content:flex-end"><div class="acoes">
    <button class="btn" data-acao="voltar">Cancelar</button><button class="btn pri" data-acao="nfSalvar">${n ? 'Salvar alterações' : 'Salvar compra'}</button></div></div>`;
}
TELAS.nota.editavel = true;
TELAS.nota.depoisForm = el => desenharItensNota(el);

function desenharItensNota(el = document) {
  const box = $('#nfItens', el); if (!box) return;
  box.innerHTML = S.formNota.itens.map((i, k) => `
    <div class="ing" data-k="${k}">
      <div class="l1"><input type="text" class="nome" data-c="descricao" placeholder="Produto ou serviço" value="${esc(i.descricao)}">
        <button class="btn lev peq" data-acao="nfRemItem" data-k="${k}" aria-label="Remover item">✕</button></div>
      <div class="linha-campos" style="margin-top:8px">
        <label class="campo"><span>Quantidade</span><input type="text" inputmode="decimal" data-c="quantidade" value="${numBR(i.quantidade)}"></label>
        <label class="campo"><span>Unidade</span><select data-c="unidade">${UNIDADES_NOTA.map(u => `<option ${u === (i.unidade || 'UN') ? 'selected' : ''}>${u}</option>`).join('')}</select></label>
        <label class="campo"><span>Valor unitário (R$)</span><input type="text" inputmode="decimal" data-c="valorUnitario" value="${i.valorUnitario != null ? numBR(i.valorUnitario, 4) : ''}"></label>
        <label class="campo"><span>Categoria</span><select data-c="categoria">${categorias().map(c => `<option ${c === i.categoria ? 'selected' : ''}>${esc(c)}</option>`).join('')}</select></label>
      </div>
    </div>`).join('');
  $$('[data-c]', box).forEach(inp => inp.addEventListener('input', () => {
    const k = +inp.closest('[data-k]').dataset.k, c = inp.dataset.c;
    S.formNota.itens[k][c] = (c === 'quantidade' || c === 'valorUnitario') ? lerNum(inp.value) : inp.value;
    if (c === 'quantidade' || c === 'valorUnitario') { delete S.formNota.itens[k].valorTotal; delete S.formNota.itens[k].valorAjustado; }
    totalNota();
  }));
  totalNota();
}
function totalNota() {
  const t = S.formNota.itens.reduce((s, i) => s + (i.valorTotal ?? (i.quantidade || 0) * (i.valorUnitario || 0)), 0);
  const d = lerNum(($('#nfDesc') || {}).value) || 0;
  const e = $('#nfTotal'); if (e) e.textContent = 'Total ' + brl(t - d);
}
ACOES.nfAddItem = () => { S.formNota.itens.push({ descricao: '', quantidade: 1, unidade: 'UN', valorUnitario: null, categoria: 'Insumos' }); desenharItensNota(); $$('#nfItens .nome').pop().focus(); };
ACOES.nfRemItem = el => { S.formNota.itens.splice(+el.dataset.k, 1); desenharItensNota(); };
ACOES.nfSalvar = async () => {
  const f = S.formNota;
  const itens = f.itens.filter(i => (i.descricao || '').trim()).map(i => {
    const q = i.quantidade || 1, vu = i.valorUnitario || 0;
    return { ...i, descricao: i.descricao.trim(), quantidade: q, valorUnitario: vu, valorTotal: i.valorTotal ?? Math.round(q * vu * 100) / 100 };
  });
  if (!itens.length) return toast('Adicione pelo menos um item com descrição.', true);
  const desconto = lerNum($('#nfDesc').value) || 0;
  const bruto = itens.reduce((s, i) => s + i.valorTotal, 0);
  const fator = bruto > 0 && desconto > 0 ? (bruto - desconto) / bruto : 1;
  itens.forEach(i => { if (desconto > 0 || i.valorAjustado == null) i.valorAjustado = Math.round(i.valorTotal * fator * 100) / 100; });
  const rec = { ...f, emitente: $('#nfLoja').value.trim(), emissao: ($('#nfData').value || hoje()) + (f.emissao && f.emissao.length > 10 ? f.emissao.slice(10) : 'T00:00:00'),
    pagamento: $('#nfPag').value, cnpj: $('#nfCnpj').value.trim(), numero: $('#nfNum').value.trim(), itens, desconto, valorTotal: Math.round((bruto - desconto) * 100) / 100 };
  if (!rec.emitente) return toast('Informe a loja ou fornecedor.', true);
  try { const r = await salvar('notas', rec); toast('Compra salva.'); ir('nota/' + r.id); } catch (e) { falha(e); }
};
document.addEventListener('input', e => { if (e.target.id === 'nfDesc') totalNota(); });
