// Lê a página de consulta da NFC-e (SEFAZ do estado) e devolve emitente, totais e itens.
// Usado de dois jeitos: na interface (com a página baixada e lida por DOMParser) e
// injetado no WebView do Android, quando o site precisa de JavaScript ou captcha.
// Só usa APIs básicas do DOM.
var GC_EXTRATOR = (function () {
  function t(el) {
    return el ? (el.textContent || '').replace(/ /g, ' ').replace(/\s+/g, ' ').trim() : '';
  }
  function q(sel, root) { return (root || document).querySelector(sel); }
  function qa(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  // "Qtde.: 2" -> "2" ; "Vl. Unit.: 24,90" -> "24,90"
  function semRotulo(s) { return s.indexOf(':') >= 0 ? s.replace(/^[^:]*:\s*/, '').trim() : s.trim(); }
  function achar(re, texto) { var m = re.exec(texto); return m ? m[1].trim() : ''; }

  function extrair(doc) {
    var r = { emitente: '', cnpj: '', endereco: '', numero: '', serie: '', emissao: '', chave: '',
              total: '', pagamento: '', itens: [], layout: '' };
    var corpo = t(doc.body);

    // ---------- Layout padrão do portal da NFC-e (RJ e vários outros estados) ----------
    qa('#tabResult tr', doc).forEach(function (tr) {
      var nome = q('span.txtTit', tr) || q('.txtTit', tr);
      var valor = q('.valor', tr);
      if (!nome || !valor) return;
      r.itens.push({
        descricao: t(nome),
        codigo: semRotulo(t(q('.RCod', tr)).replace(/[()]/g, '')),
        quantidade: semRotulo(t(q('.Rqtd', tr))),
        unidade: semRotulo(t(q('.RUN', tr))),
        valorUnitario: semRotulo(t(q('.RvlUnit', tr))),
        valorTotal: t(valor)
      });
    });
    if (r.itens.length) r.layout = 'nacional';

    // ---------- Alternativa: qualquer tabela com cabeçalho Descrição / Qtde / Valor ----------
    if (!r.itens.length) {
      qa('table', doc).some(function (tab) {
        var linhas = qa('tr', tab);
        for (var h = 0; h < linhas.length; h++) {
          var cab = qa('th,td', linhas[h]).map(function (c) { return t(c).toLowerCase(); });
          var col = { d: -1, c: -1, q: -1, u: -1, vu: -1, vt: -1 };
          cab.forEach(function (c, i) {
            if (/descri|produto/.test(c) && col.d < 0) col.d = i;
            else if (/^c[óo]d/.test(c) && col.c < 0) col.c = i;
            else if (/^qt|quant/.test(c) && col.q < 0) col.q = i;
            else if (/^un/.test(c) && col.u < 0) col.u = i;
            else if (/unit/.test(c) && col.vu < 0) col.vu = i;
            else if (/(total|valor)/.test(c)) col.vt = i;
          });
          if (col.d < 0 || col.vt < 0) continue;
          for (var k = h + 1; k < linhas.length; k++) {
            var cs = qa('td', linhas[k]).map(t);
            if (cs.length <= Math.max(col.d, col.vt) || !cs[col.d] || !/\d/.test(cs[col.vt])) continue;
            r.itens.push({
              descricao: cs[col.d],
              codigo: col.c >= 0 ? cs[col.c] || '' : '',
              quantidade: col.q >= 0 ? cs[col.q] || '1' : '1',
              unidade: col.u >= 0 ? cs[col.u] || '' : '',
              valorUnitario: col.vu >= 0 ? cs[col.vu] || '' : '',
              valorTotal: cs[col.vt]
            });
          }
          if (r.itens.length) { r.layout = 'tabela'; return true; }
        }
        return false;
      });
    }

    // ---------- Cabeçalho ----------
    r.emitente = t(q('#u20', doc)) || t(q('.txtTopo', doc));
    r.cnpj = achar(/CNPJ:?\s*([\d.\/-]{14,18})/i, corpo);
    qa('.txtCenter .text, #conteudo .text', doc).some(function (el) {
      var s = t(el);
      if (s && !/CNPJ|CPF|Inscri/i.test(s)) { r.endereco = s; return true; }
      return false;
    });

    // ---------- Totais e pagamento ----------
    // "Valor a pagar" (já com descontos) tem preferência sobre "Valor total"
    [/valor a pagar/, /valor total/].forEach(function (re) {
      if (r.total) return;
      qa('#linhaTotal, .linhaTotal', doc).forEach(function (l) {
        if (!r.total && re.test(t(q('label', l)).toLowerCase())) r.total = t(q('.totalNumb', l));
      });
    });
    if (!r.total) r.total = t(q('span.totalNumb.txtMax', doc));
    if (!r.total) r.total = achar(/Valor (?:a pagar|total)[^\d]{0,20}([\d.]+,\d{2})/i, corpo);
    r.pagamento = t(q('#linhaTotal label.tx', doc)) || t(q('label.tx', doc));

    // ---------- Informações gerais ----------
    r.numero = achar(/N[úu]mero:?\s*(\d+)/i, corpo);
    r.serie = achar(/S[ée]rie:?\s*(\d+)/i, corpo);
    r.emissao = achar(/Emiss[ãa]o:?\s*(\d{2}\/\d{2}\/\d{4}\s+\d{2}:\d{2}(?::\d{2})?)/i, corpo)
             || achar(/(\d{2}\/\d{2}\/\d{4}\s+\d{2}:\d{2}:\d{2})/, corpo);
    r.chave = t(q('.chave', doc)).replace(/\D/g, '');
    if (r.chave.length !== 44) {
      var m = /((?:\d{4}\s?){10}\d{4})/.exec(corpo);
      r.chave = m ? m[1].replace(/\D/g, '') : '';
    }
    return r;
  }

  function extrairJanela() {
    // A página pode estar dentro de frames (alguns estados usam).
    var docs = [document];
    for (var i = 0; i < window.frames.length; i++) {
      try { if (window.frames[i].document) docs.push(window.frames[i].document); } catch (e) { /* outra origem */ }
    }
    var melhor = null;
    for (var j = 0; j < docs.length; j++) {
      var r = extrair(docs[j]);
      if (!melhor || r.itens.length > melhor.itens.length) melhor = r;
    }
    melhor.temCaptcha = /captcha/i.test(document.documentElement.innerHTML);
    return JSON.stringify(melhor);
  }

  return { extrair: extrair, extrairJanela: extrairJanela };
})();
