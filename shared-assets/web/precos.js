/* Pesquisar preço: busca um produto nos sites que o usuário cadastrou e compara os preços. */
'use strict';

/** "Choc gt melk bc 2,05kg" → "choc gt melk bc" (tira tamanhos e códigos, que atrapalham a busca nos sites). */
function termoBusca(nome) {
  return String(nome || '').replace(/\b\d+([.,]\d+)?\s*(kg|g|gr|ml|l|lt|un|und)\b/gi, ' ').replace(/[.()/\\_-]+/g, ' ')
    .replace(/\b\d+\b/g, ' ').replace(/\s+/g, ' ').trim().toLowerCase();
}

/* ======================================================================= leitura de preços nas páginas */

const LeitorPrecos = {
  /** Converte "R$ 1.234,56", "1234.56", 12.3 em número. */
  numero(v) {
    if (typeof v === 'number') return isFinite(v) ? v : null;
    let t = String(v ?? '').replace(/[^\d.,]/g, '');
    if (!t) return null;
    if (t.includes(',')) t = t.replace(/\./g, '').replace(',', '.');
    else if ((t.match(/\./g) || []).length > 1 || /\.\d{3}$/.test(t)) t = t.replace(/\./g, '');
    const n = parseFloat(t);
    return isFinite(n) && n > 0 ? n : null;
  },

  /** Lista de produtos {nome, preco, url, imagem} encontrados num documento HTML. */
  extrair(doc, base) {
    const achados = [], vistos = new Set();
    const abs = h => { try { return new URL(h, base).href; } catch (e) { return ''; } };
    const add = (nome, preco, url, imagem, fonte) => {
      nome = String(nome || '').replace(/\s+/g, ' ').trim(); preco = LeitorPrecos.numero(preco); url = url ? abs(url) : '';
      if (!nome || nome.length < 3 || !preco || preco > 1e6) return;
      const chave = (url || nome) + '|' + preco.toFixed(2);
      if (vistos.has(chave)) return; vistos.add(chave);
      achados.push({ nome, preco, url, imagem: imagem ? abs(imagem) : '', fonte });
    };
    LeitorPrecos.jsonLd(doc, add);
    LeitorPrecos.vtexEstado(doc, add, base);
    LeitorPrecos.microdados(doc, add);
    LeitorPrecos.mercadoLivre(doc, add);
    if (achados.length < 3) LeitorPrecos.generico(doc, add);
    return achados;
  },

  /** Produtos da API de catálogo das lojas VTEX (/api/catalog_system/pub/products/search). */
  vtexApi(lista, base) {
    const r = [];
    (Array.isArray(lista) ? lista : []).forEach(p => {
      const item = (p.items || [])[0] || {};
      const oferta = ((item.sellers || []).find(s => s.sellerDefault) || (item.sellers || [])[0] || {}).commertialOffer || {};
      const preco = oferta.Price || oferta.spotPrice;
      if (!preco || oferta.AvailableQuantity === 0) return;
      let url = p.link || (p.linkText ? '/' + p.linkText + '/p' : '');
      try { url = new URL(url, base).href; } catch (e) { }
      r.push({ nome: p.productName || item.name, preco, url, imagem: ((item.images || [])[0] || {}).imageUrl || '', fonte: 'vtex' });
    });
    return r;
  },

  jsonLd(doc, add) {
    const visitar = (o, url) => {
      if (!o || typeof o !== 'object') return;
      if (Array.isArray(o)) { o.forEach(x => visitar(x, url)); return; }
      const tipo = [].concat(o['@type'] || []).join(' ');
      if (/Product/i.test(tipo)) {
        const ofs = [].concat(o.offers || []);
        let preco = null;
        ofs.forEach(of => { const v = LeitorPrecos.numero(of.price ?? of.lowPrice ?? (of.priceSpecification || {}).price); if (v && (preco == null || v < preco)) preco = v; });
        add(o.name, preco, o.url || url || (ofs[0] || {}).url, [].concat(o.image || [])[0], 'json-ld');
      }
      if (/ItemList/i.test(tipo)) [].concat(o.itemListElement || []).forEach(e => visitar(e.item || e, e.url));
      if (o['@graph']) visitar(o['@graph']);
    };
    doc.querySelectorAll('script[type="application/ld+json"]').forEach(s => { try { visitar(JSON.parse(s.textContent)); } catch (e) { } });
  },

  /** Lojas VTEX IO trazem os produtos no estado da página (__STATE__). */
  vtexEstado(doc, add, base) {
    doc.querySelectorAll('template[data-varname="__STATE__"], script[data-varname="__STATE__"]').forEach(t => {
      let st; try { st = JSON.parse((t.content ? t.content.textContent : '') || t.textContent); } catch (e) { return; }
      Object.keys(st).forEach(k => {
        const p = st[k];
        if (!/^Product:/.test(k) || !p || !p.productName) return;
        let preco = null;
        const faixa = st['$' + k + '.priceRange.sellingPrice'];
        if (faixa) preco = faixa.lowPrice || faixa.highPrice;
        if (!preco) Object.keys(st).filter(x => x.startsWith('$' + k + '.items') && x.endsWith('commertialOffer')).forEach(x => { preco = preco || st[x].Price || st[x].spotPrice; });
        add(p.productName, preco, p.link || (p.linkText ? '/' + p.linkText + '/p' : ''), '', 'vtex');
      });
    });
  },

  microdados(doc, add) {
    doc.querySelectorAll('[itemtype*="schema.org/Product"]').forEach(el => {
      const nome = el.querySelector('[itemprop="name"]');
      const preco = el.querySelector('[itemprop="price"], [itemprop="lowPrice"]');
      const link = el.querySelector('a[href]');
      if (nome && preco) add(nome.getAttribute('content') || nome.textContent, preco.getAttribute('content') || preco.textContent, link && link.getAttribute('href'), '', 'microdados');
    });
  },

  /** Preço no formato do Mercado Livre (andes-money-amount: inteiro e centavos em partes separadas). */
  precoAndes(el) {
    if (!el) return null;
    const fr = el.querySelector('.andes-money-amount__fraction');
    if (fr) {
      const ct = el.querySelector('.andes-money-amount__cents');
      return LeitorPrecos.numero(fr.textContent.replace(/\D/g, '') + (ct ? ',' + ct.textContent.replace(/\D/g, '') : ''));
    }
    return LeitorPrecos.numero(el.textContent);
  },

  mercadoLivre(doc, add) {
    doc.querySelectorAll('.poly-card, .ui-search-result__wrapper, li.ui-search-layout__item').forEach(c => {
      const t = c.querySelector('a.poly-component__title, .poly-component__title a, .poly-component__title, h2.ui-search-item__title, .ui-search-item__title, a.ui-search-link');
      const link = (t && t.closest('a[href]')) || (t && t.querySelector('a[href]')) || c.querySelector('a[href]');
      const preco = c.querySelector('.poly-price__current .andes-money-amount, .ui-search-price__second-line .andes-money-amount')
        || [...c.querySelectorAll('.andes-money-amount')].find(e => !e.closest('s, del, .andes-money-amount--previous, .ui-search-price__original-value, .poly-price__installments'));
      if (t && link) add(t.textContent, LeitorPrecos.precoAndes(preco), link.getAttribute('href'), (c.querySelector('img') || {}).src, 'mercadolivre');
    });
  },

  /**
   * Leitura genérica: acha cada texto de preço ("R$ 12,90"), sobe na página até o "cartão" que tem
   * um único link de produto e usa o texto desse link como nome. Ignora preço riscado e parcelado.
   */
  generico(doc, add) {
    const ehRiscado = el => !!el.closest('s, del, strike, [class*="old" i], [class*="antigo" i], [class*="list-price" i], [class*="listprice" i], [class*="from" i], [class*="riscad" i], [class*="antes" i], [class*="original" i], [class*="regular" i], [class*="was" i], [class*="installment" i], [class*="parcel" i]');
    // texto logo antes do preço (ex.: "De R$ 10,00", "3x de R$ 5,00"): indica preço antigo ou parcela
    const textoAntes = el => {
      let t = '', n = el;
      while (n && t.length < 20) { let irmao = n.previousSibling; while (irmao && t.length < 20) { t = (irmao.textContent || '') + t; irmao = irmao.previousSibling; } n = n.parentElement; if (n && /R\$/.test(t)) break; }
      return t.slice(-20);
    };
    const nomeLink = a => (a.textContent.replace(/\s+/g, ' ').trim() || a.getAttribute('title') || (a.querySelector('img[alt]') || {}).alt || '').trim();
    const links = el => [...el.querySelectorAll('a[href]')].filter(a => { const h = a.getAttribute('href') || ''; return h && !/^(#|javascript:|mailto:|tel:)/i.test(h) && nomeLink(a).length >= 6; });
    const precos = [...doc.body.querySelectorAll('*')].filter(e => {
      if (/^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE|OPTION)$/.test(e.tagName)) return false;
      const t = e.textContent; if (!t || t.length > 60 || !/R\$\s*\d/.test(t)) return false;
      return ![...e.children].some(f => /R\$\s*\d/.test(f.textContent));   // só o elemento mais interno
    });
    const usados = new Set();
    precos.forEach(p => {
      if (ehRiscado(p)) return;
      const proprio = p.textContent.split('R$')[0];   // texto dentro do próprio elemento antes do "R$"
      const antes = textoAntes(p) + proprio;
      if (/\d+\s*x\s*(de\s*)?$/i.test(antes.trim()) || /\d+\s*x\s*(de)?\s*R\$/i.test(p.textContent)) return;   // parcela
      if (/(^|\s)de\s*:?$/i.test(antes.trim())) return;                                                      // "De R$ 10,00" = preço antigo
      let el = p.parentElement;
      for (let n = 0; el && n < 8; n++, el = el.parentElement) {
        const ls = links(el), hrefs = new Set(ls.map(a => a.getAttribute('href')));
        if (hrefs.size > 1) return;
        if (hrefs.size === 1) {
          if (usados.has(el)) return; usados.add(el);
          const a = ls.reduce((x, y) => nomeLink(y).length > nomeLink(x).length ? y : x);
          const tit = el.querySelector('h1, h2, h3, h4, [class*="name" i], [class*="title" i], [class*="nome" i]');
          const nome = (tit && tit.textContent.trim().length >= 6 && !/R\$/.test(tit.textContent)) ? tit.textContent : nomeLink(a);
          add(nome, p.textContent.match(/R\$\s*([\d.]+(?:,\d{1,2})?)/)[1], a.getAttribute('href'), (el.querySelector('img') || {}).src, 'generico');
          return;
        }
      }
    });
  },
};

/* ======================================================================= busca nos sites */

const BuscaPrecos = {
  origem(url) { try { return new URL(url).origin; } catch (e) { return ''; } },
  host(url) { try { return new URL(url).hostname.replace(/^www\./, ''); } catch (e) { return url; } },

  preencher(modelo, q) {
    return modelo.replace('{busca-}', encodeURIComponent(q.trim().toLowerCase().replace(/\s+/g, '-')))
      .replace('{busca+}', encodeURIComponent(q.trim()).replace(/%20/g, '+'))
      .replace('{busca}', encodeURIComponent(q.trim()));
  },

  /** Endereços de busca conhecidos de alguns sites grandes. */
  conhecidos: [
    [/mercadolivre\.com\.br$/, 'https://lista.mercadolivre.com.br/{busca-}'],
    [/amazon\.com\.br$/, 'https://www.amazon.com.br/s?k={busca+}'],
    [/magazineluiza\.com\.br$/, 'https://www.magazineluiza.com.br/busca/{busca-}/'],
  ],

  async baixar(url) {
    const r = await GC.chamar('buscarSite', { url });
    const texto = r.html || '';
    if (/json/i.test(r.tipo) || /^\s*[\[{]/.test(texto.slice(0, 20))) { try { return { url: r.url, json: JSON.parse(texto) }; } catch (e) { } }
    return { url: r.url, doc: new DOMParser().parseFromString(texto, 'text/html') };
  },

  ler(pg, q) {
    if (pg.json) return LeitorPrecos.vtexApi(pg.json, pg.url);
    return LeitorPrecos.extrair(pg.doc, pg.url);
  },

  /** Lê a página de busca do próprio site para descobrir o endereço de busca (OpenSearch ou formulário). */
  async descobrir(site) {
    const pg = await BuscaPrecos.baixar(site.url);
    if (!pg.doc) return [];
    const modelos = [];
    const os = pg.doc.querySelector('link[rel="search"][type*="opensearch"]');
    if (os) {
      try {
        const x = await BuscaPrecos.baixar(new URL(os.getAttribute('href'), pg.url).href);
        const u = x.doc && x.doc.querySelector('Url[type="text/html"], url[type="text/html"]');
        if (u) modelos.push(new URL(u.getAttribute('template').replace('{searchTerms}', '{busca}'), pg.url).href);
      } catch (e) { }
    }
    pg.doc.querySelectorAll('form').forEach(f => {
      const inp = f.querySelector('input[type="search"], input[name="q"], input[name="s"], input[name="busca"], input[name="termo"], input[name="ft"], input[name="search"], input[name="query"], input[name="k"]');
      if (!inp || (f.getAttribute('method') || 'get').toLowerCase() !== 'get') return;
      const acao = new URL(f.getAttribute('action') || pg.url, pg.url);
      const ps = new URLSearchParams();
      f.querySelectorAll('input[type="hidden"][name]').forEach(h => ps.set(h.name, h.value));
      ps.set(inp.name || 'q', '__BUSCA__');
      modelos.push(acao.origin + acao.pathname + '?' + ps.toString().replace('__BUSCA__', '{busca}'));
    });
    return modelos;
  },

  /**
   * Procura o produto num site. Tenta, nesta ordem: o endereço de busca já conhecido do site,
   * a API de lojas VTEX, a busca declarada pela página e endereços comuns. O que funcionar é lembrado.
   */
  async buscar(site, q, aoProgresso) {
    const origem = BuscaPrecos.origem(site.url), host = BuscaPrecos.host(site.url);
    const tentativas = [];
    if (site.busca) tentativas.push({ modelo: site.busca });
    const conhecido = BuscaPrecos.conhecidos.find(([re]) => re.test(host));
    if (conhecido) tentativas.push({ modelo: conhecido[1] });
    tentativas.push({ modelo: origem + '/api/catalog_system/pub/products/search?ft={busca}&_from=0&_to=19' });
    tentativas.push({ descobrir: true });
    ['/busca?q={busca}', '/{busca}?_q={busca}&map=ft', '/search?q={busca}', '/?s={busca}&post_type=product', '/pesquisa?t={busca}']
      .forEach(c => tentativas.push({ modelo: origem + c }));

    const vistos = new Set();
    let ultimoErro = null, paginaParaRenderizar = null;
    for (let k = 0; k < tentativas.length; k++) {
      let modelos = tentativas[k].modelo ? [tentativas[k].modelo] : [];
      if (tentativas[k].descobrir) { try { modelos = await BuscaPrecos.descobrir(site); } catch (e) { ultimoErro = e; } }
      for (const modelo of modelos) {
        if (vistos.has(modelo)) continue; vistos.add(modelo);
        const url = BuscaPrecos.preencher(modelo, q);
        aoProgresso && aoProgresso(url);
        try {
          const pg = await BuscaPrecos.baixar(url);
          const itens = BuscaPrecos.ler(pg, q);
          if (itens.length) return { itens, modelo, url };
          if (pg.doc && !paginaParaRenderizar && !/\/api\//.test(modelo)) paginaParaRenderizar = { url, modelo };
        } catch (e) { ultimoErro = e; }
      }
    }
    // Celular: a página pode ser montada por JavaScript; abre num navegador interno e lê de novo
    if (ehAndroid && paginaParaRenderizar) {
      try {
        aoProgresso && aoProgresso(paginaParaRenderizar.url, true);
        const r = await BuscaPrecos.renderizar(paginaParaRenderizar.url);
        const itens = LeitorPrecos.extrair(new DOMParser().parseFromString(r.html, 'text/html'), r.url);
        if (itens.length) return { itens, modelo: paginaParaRenderizar.modelo, url: paginaParaRenderizar.url, renderizado: true };
      } catch (e) { ultimoErro = e; }
    }
    const err = new Error(ultimoErro && /erro 4\d\d|bloque|403|429/i.test(ultimoErro.message)
      ? 'O site recusou a consulta automática. Abra a busca no site.'
      : 'Não encontrei preços neste site. Abra a busca no site ou informe o endereço de busca dele.');
    err.urlBusca = paginaParaRenderizar ? paginaParaRenderizar.url : (site.busca ? BuscaPrecos.preencher(site.busca, q) : site.url);
    throw err;
  },

  /** O navegador interno do celular abre uma página por vez: as chamadas entram numa fila. */
  fila: Promise.resolve(),
  renderizar(url) {
    const p = BuscaPrecos.fila.then(() => GC.chamar('nativo.renderizar', { url }));
    BuscaPrecos.fila = p.catch(() => { });
    return p;
  },

  /** 0..1: quanto do termo aparece no nome do produto. */
  relevancia(nome, q) {
    const tk = s => norm(s).replace(/[^A-Z0-9 ]/g, ' ').split(/\s+/).filter(x => x.length >= 2);
    const n = ' ' + tk(nome).join(' ') + ' ', t = tk(q);
    if (!t.length) return 0;
    return t.filter(x => n.includes(' ' + x) ).length / t.length;
  },
};

/* ======================================================================= tela */

S.precos = { q: '', resultados: {}, buscando: false };

TELAS.precos = r => {
  const sites = (S.dados.sitesPreco || []).slice().sort((a, b) => (a.ordem || 0) - (b.ordem || 0) || a.nome.localeCompare(b.nome));
  const ins = r.params.insumo ? porId('insumos', r.params.insumo) : null;
  if (r.params.q != null && r.params.q !== S.precos.qRota) { S.precos.qRota = r.params.q; S.precos.q = r.params.q; S.precos.resultados = {}; S.precos.auto = !!sites.length; }
  return `
  <div class="cabeca"><div><h1>Pesquisar preço</h1><p class="sec">Compare o preço de um produto nos sites que você escolher.</p></div></div>
  <section class="bloco busca-preco">
    <form id="fBusca" style="display:flex;gap:8px;flex-wrap:wrap">
      <input type="search" id="pQ" value="${esc(S.precos.q)}" placeholder="Ex.: leite condensado 395g, chocolate meio amargo 1kg" style="flex:1;min-width:220px" ${sites.length ? '' : 'disabled'}>
      <button class="btn pri" type="submit" ${sites.length ? '' : 'disabled'}>Pesquisar</button>
    </form>
    ${ins && ins.ultimoExib != null ? `<p class="sec" style="margin-top:8px">Referência: você pagou <b>${brl(ins.ultimoExib)}/${esc(ins.unidadeExib)}</b> em ${esc(ins.ultimaCompra.loja)}, ${dataBR(ins.ultimaCompra.data)}.</p>` : ''}
    ${historico().length ? `<div class="chips" style="margin-top:10px">${historico().map(h => `<button class="chip" data-acao="buscaRecente" data-q="${esc(h)}">${esc(h)}</button>`).join('')}</div>` : ''}
  </section>
  <div id="pResultados">${sites.length ? resultadosHtml(sites, ins) : ''}</div>
  <section class="bloco" style="margin-top:14px"><header><h2>Sites para comparar</h2><button class="btn lev peq" data-acao="novoSite">${ICONES.mais} Adicionar site</button></header>
    ${sites.length ? `<ul class="lista">${sites.map(s => `<li style="cursor:default">
        <div class="meio"><div class="titulo">${esc(s.nome)}</div><div class="sec peq">${esc(BuscaPrecos.host(s.url))}${s.busca ? ', endereço de busca conhecido' : ''}</div></div>
        <button class="btn lev peq" data-acao="editarSite" data-id="${s.id}">Editar</button>
        <button class="btn lev peq perigo" data-acao="removerSite" data-id="${s.id}" aria-label="Remover ${esc(s.nome)}">Remover</button></li>`).join('')}</ul>`
      : `<div class="vazio" style="padding:22px 10px"><p>Nenhum site cadastrado.</p><p class="sec">Adicione o endereço dos sites onde você costuma comprar, por exemplo o de um supermercado ou do Mercado Livre.</p>
         <button class="btn pri" data-acao="novoSite">${ICONES.mais} Adicionar site</button></div>`}
  </section>`;
};
TELAS.precos.editavel = true;   // não redesenha a tela no meio de uma pesquisa
TELAS.precos.depois = (el, r) => {
  const f = $('#fBusca', el);
  if (f) f.onsubmit = e => { e.preventDefault(); pesquisar($('#pQ', el).value, r.params.insumo); };
  if (S.precos.auto) { S.precos.auto = false; pesquisar(S.precos.q, r.params.insumo); }
};

function historico() { try { return JSON.parse(localStorage.getItem('gc.buscasPreco') || '[]'); } catch (e) { return S.precos.hist || []; } }
function lembrarBusca(q) {
  const h = [q, ...historico().filter(x => x.toLowerCase() !== q.toLowerCase())].slice(0, 6);
  S.precos.hist = h; try { localStorage.setItem('gc.buscasPreco', JSON.stringify(h)); } catch (e) { }
}
ACOES.buscaRecente = el => { $('#pQ').value = el.dataset.q; pesquisar(el.dataset.q, rotaAtual().params.insumo); };

async function pesquisar(q, insumoId) {
  q = (q || '').trim(); if (!q) return toast('Digite o produto.', true);
  const sites = S.dados.sitesPreco || [];
  S.precos.q = q; S.precos.resultados = {}; S.precos.buscando = true;
  lembrarBusca(q);
  sites.forEach(s => { S.precos.resultados[s.id] = { estado: 'buscando' }; });
  const ins = insumoId ? porId('insumos', insumoId) : null;
  const redesenhar = () => { const box = $('#pResultados'); if (box) box.innerHTML = resultadosHtml(sites, ins); };
  redesenhar();
  await Promise.all(sites.map(async s => {
    try {
      const r = await BuscaPrecos.buscar(s, q, (url, render) => { S.precos.resultados[s.id] = { estado: 'buscando', detalhe: render ? 'abrindo a página' : '' }; });
      let itens = r.itens.map(i => ({ ...i, rel: BuscaPrecos.relevancia(i.nome, q) }));
      const melhor = Math.max(...itens.map(i => i.rel));
      itens = itens.filter(i => i.rel >= Math.min(0.5, melhor)).sort((a, b) => b.rel - a.rel || a.preco - b.preco).slice(0, 6);
      const med = await GC.chamar('medidas', { itens: itens.map(i => ({ nome: i.nome, preco: i.preco })) });
      itens.forEach((i, k) => { i.medida = med[k]; });
      S.precos.resultados[s.id] = { estado: 'ok', itens, urlBusca: r.url };
      // lembra o endereço de busca que funcionou (fica sincronizado entre os aparelhos)
      if (r.modelo && r.modelo !== s.busca) { s.busca = r.modelo; GC.chamar('salvar', { col: 'sitesPreco', rec: { ...s } }).catch(() => { }); }
    } catch (e) {
      S.precos.resultados[s.id] = { estado: 'erro', erro: e.message, urlBusca: e.urlBusca };
    }
    redesenhar();
  }));
  S.precos.buscando = false;
  redesenhar();
}

/** Preço para comparar entre sites: por kg/L/un quando dá para saber, senão o preço da embalagem. */
const valorComparavel = i => i.medida ? i.medida.precoExib : null;

function resultadosHtml(sites, ins) {
  const res = S.precos.resultados;
  if (!Object.keys(res).length) return '';
  // melhor oferta de cada site: o item mais relevante e mais barato
  const melhores = sites.map(s => { const r = res[s.id]; return r && r.estado === 'ok' && r.itens.length ? { site: s, item: r.itens[0] } : null; }).filter(Boolean);
  const porUnidade = melhores.filter(m => valorComparavel(m.item) != null);
  const unidadeComum = porUnidade.length >= 2 && new Set(porUnidade.map(m => m.item.medida.unidadeExib)).size === 1;
  const ordenados = melhores.slice().sort((a, b) => unidadeComum
    ? (valorComparavel(a.item) ?? 1e9) - (valorComparavel(b.item) ?? 1e9) : a.item.preco - b.item.preco);
  const refKg = ins && ins.ultimoExib != null ? ins.ultimoExib : null;
  const vsRef = i => refKg && i.medida && i.medida.unidadeExib === ins.unidadeExib ? (i.medida.precoExib / refKg - 1) : null;
  const resumo = ordenados.length ? `<section class="bloco"><header><h2>Comparação</h2><span class="sec peq">${unidadeComum ? 'ordenado pelo preço por ' + esc(porUnidade[0].item.medida.unidadeExib) : 'ordenado pelo preço'}</span></header>
    <div class="rolar comp-tabela"><table class="tab"><thead><tr><th>Site</th><th>Produto</th><th class="n">Preço</th><th class="n">Por kg/L/un</th>${refKg ? '<th class="n">Vs. sua compra</th>' : ''}<th></th></tr></thead><tbody>
    ${ordenados.map((m, k) => { const v = vsRef(m.item); return `<tr><td>${k === 0 && ordenados.length > 1 ? '<span class="selo ok" style="margin-right:6px">mais barato</span>' : ''}<b>${esc(m.site.nome)}</b></td>
      <td>${esc(m.item.nome)}</td><td class="n"><b>${brl(m.item.preco)}</b></td>
      <td class="n">${m.item.medida ? brl(m.item.medida.precoExib) + '/' + esc(m.item.medida.unidadeExib) : '—'}</td>
      ${refKg ? `<td class="n">${v == null ? '—' : `<span class="${v <= 0 ? 'det bom' : 'det ruim'}">${v <= 0 ? '▼' : '▲'} ${pct(Math.abs(v))}</span>`}</td>` : ''}
      <td class="n">${m.item.url ? `<a class="btn peq" href="${esc(m.item.url)}" target="_blank" rel="noopener">Abrir</a>` : ''}</td></tr>`; }).join('')}
    </tbody></table></div>
    <ul class="lista comp-lista">${ordenados.map((m, k) => { const v = vsRef(m.item); return `<li style="cursor:default"><div class="meio">
        <div class="titulo">${esc(m.site.nome)}${k === 0 && ordenados.length > 1 ? ' <span class="selo ok">mais barato</span>' : ''}</div>
        <div class="sec peq" style="white-space:normal">${esc(m.item.nome)}</div>
        <div class="sec peq">${m.item.medida ? brl(m.item.medida.precoExib) + '/' + esc(m.item.medida.unidadeExib) : ''}${v == null ? '' : ` <span class="${v <= 0 ? 'det bom' : 'det ruim'}">${v <= 0 ? '▼' : '▲'} ${pct(Math.abs(v))} vs sua compra</span>`}</div></div>
        <div class="valor">${brl(m.item.preco)}</div>${m.item.url ? `<a class="btn peq" href="${esc(m.item.url)}" target="_blank" rel="noopener">Abrir</a>` : ''}</li>`; }).join('')}</ul>
    <p class="sec peq" style="margin-top:6px">Preços lidos dos sites agora. Confira no site antes de comprar: frete, estoque e preço podem variar por região.</p></section>` : '';
  const blocos = sites.map(s => {
    const r = res[s.id]; if (!r) return '';
    let corpo;
    if (r.estado === 'buscando') corpo = `<p class="sec"><span class="carregando"></span> Procurando${r.detalhe ? ' (' + esc(r.detalhe) + ')' : ''}…</p>`;
    else if (r.estado === 'erro') corpo = `<p class="sec">${esc(r.erro)}</p>${r.urlBusca ? `<p style="margin-top:6px"><a class="btn peq" href="${esc(r.urlBusca)}" target="_blank" rel="noopener">Abrir a busca no site</a> <button class="btn lev peq" data-acao="editarSite" data-id="${s.id}">Informar endereço de busca</button></p>` : ''}`;
    else if (!r.itens.length) corpo = `<p class="sec">Nenhum produto parecido com “${esc(S.precos.q)}”.</p>`;
    else corpo = `<ul class="lista">${r.itens.map(i => `<li style="cursor:default">
        ${i.imagem ? `<img src="${esc(i.imagem)}" alt="" loading="lazy" onerror="this.remove()" style="width:44px;height:44px;object-fit:contain;border-radius:8px;background:#fff;flex:none" referrerpolicy="no-referrer">` : ''}
        <div class="meio"><div class="titulo" title="${esc(i.nome)}">${esc(i.nome)}</div>
          <div class="sec peq">${i.medida ? brl(i.medida.precoExib) + ' por ' + esc(i.medida.unidadeExib) : 'tamanho não identificado no nome'}</div></div>
        <div class="valor">${brl(i.preco)}</div>
        ${i.url ? `<a class="btn peq" href="${esc(i.url)}" target="_blank" rel="noopener">Abrir</a>` : ''}</li>`).join('')}</ul>
      ${r.urlBusca ? `<p style="margin-top:6px"><a class="sec peq" href="${esc(r.urlBusca)}" target="_blank" rel="noopener">Ver todos os resultados no site</a></p>` : ''}`;
    return `<section class="bloco"><header><h2>${esc(s.nome)}</h2><span class="sec peq">${esc(BuscaPrecos.host(s.url))}</span></header>${corpo}</section>`;
  }).join('');
  return resumo + `<div class="grade g2" style="margin-top:14px">${blocos}</div>`;
}

/* ---------- cadastro de sites (só o usuário adiciona e remove) ---------- */
ACOES.novoSite = () => editarSite(null);
ACOES.editarSite = el => editarSite(porId('sitesPreco', el.dataset.id));
ACOES.removerSite = async el => {
  const s = porId('sitesPreco', el.dataset.id);
  if (!await confirmar('Remover site?', `${s.nome} deixa de ser consultado.`, 'Remover', true)) return;
  await excluir('sitesPreco', s.id); delete S.precos.resultados[s.id]; render();
};
function editarSite(s) {
  modal(`<h2>${s ? 'Editar site' : 'Adicionar site'}</h2>
    <label class="campo"><span>Nome</span><input type="text" id="sNome" value="${esc(s ? s.nome : '')}" placeholder="Ex.: Mercado Livre"></label>
    <label class="campo"><span>Endereço do site</span><input type="text" id="sUrl" inputmode="url" value="${esc(s ? s.url : '')}" placeholder="Ex.: https://www.mercadolivre.com.br"></label>
    <details ${s && s.busca ? 'open' : ''} style="margin-bottom:10px"><summary class="sec" style="cursor:pointer">Endereço de busca (opcional)</summary>
      <p class="sec peq" style="margin:6px 0">O app tenta descobrir sozinho. Se não conseguir, faça uma busca no site, copie o endereço da página de resultados e troque o produto por <b>{busca}</b>.</p>
      <input type="text" id="sBusca" value="${esc(s && s.busca ? s.busca : '')}" placeholder="https://www.loja.com.br/busca?q={busca}"></details>
    <div class="rodape"><button class="btn" data-fechar>Cancelar</button><button class="btn pri" id="sOk">Salvar</button></div>`, (m, fechar) => {
    $('#sOk', m).onclick = async () => {
      let url = $('#sUrl', m).value.trim(), busca = $('#sBusca', m).value.trim();
      if (url && !/^https?:\/\//i.test(url)) url = 'https://' + url;
      let host; try { host = new URL(url).hostname; } catch (e) { return toast('Endereço inválido.', true); }
      if (!/\./.test(host)) return toast('Endereço inválido.', true);
      if (busca && !busca.includes('{busca')) return toast('O endereço de busca precisa ter {busca} no lugar do produto.', true);
      if (busca) { try { if (BuscaPrecos.host(busca).split('.').slice(-2).join('.') !== host.replace(/^www\./, '').split('.').slice(-2).join('.')) return toast('O endereço de busca precisa ser do mesmo site.', true); } catch (e) { return toast('Endereço de busca inválido.', true); } }
      const nome = $('#sNome', m).value.trim() || BuscaPrecos.host(url);
      try {
        await salvar('sitesPreco', { ...(s || {}), nome, url, busca: busca || (s && s.url === url ? s.busca : '') || '' });
        fechar(); toast(s ? 'Site atualizado.' : `${nome} adicionado.`); render();
      } catch (e) { falha(e); }
    };
  });
}
