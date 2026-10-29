# Gestão Confeitaria

Controle financeiro da confeitaria no celular e no computador, com os mesmos dados nos dois.

## Baixar e instalar (pelo GitHub)

A cada envio para a branch `main`, o GitHub compila tudo sozinho (aba **Actions**) e publica em
**Releases > Última versão**:

- `GestaoConfeitaria.apk`: abra o link no navegador do celular, baixe e instale
  (o Android pede para permitir a instalação pelo navegador na primeira vez).
- `GestaoConfeitaria-Windows.zip`: descompacte e abra `GestaoConfeitaria.exe` — já traz o Java, não precisa instalar nada.
- `GestaoConfeitaria-Computador-Java.zip`: para Mac/Linux ou quem já tem Java 17+ (`Abrir no Windows.bat` / `abrir.sh`).


## Primeiros passos

1. **Importe a planilha atual**: *Notas > Adicionar nota > Importar planilha* e escolha o `.xlsm`.
   As 1.114 linhas de *Notas_Consolidadas* viram 172 notas, agrupadas por número, data e loja,
   com o *Valor Total - Ajustado* preservado. Importar de novo não duplica.
2. **Revise as categorias** (o app sugere pela descrição: insumos, embalagens, utensílios, cursos…).
   Compras pessoais misturadas na mesma nota vão para *Pessoal (fora do negócio)* e saem do resumo.
3. **Pareie os aparelhos** em *Mais > Aparelhos*.

## Receitas e preço tabelado

Ao escolher um ingrediente "da nota fiscal", o app descobre quanto vem na embalagem:

| Na nota | O app entende | Preço tabelado |
|---|---|---|
| `CHOC.GT.MELK.BC.2,05KG`, 1 UN por R$ 89,99 | 2.050 g | R$ 43,90/kg |
| `BANANA PRATA KG`, 1,235 KG | vendido por kg | preço do kg |
| `OVOS C/30` | 30 unidades | R$/un |
| `LEITE C.I.8 P.1.C.395` | não identificado | **pede o conteúdo** (sugere 395 g) |

Na receita, *200 g* desse chocolate custam R$ 8,78. Colheres e xícaras são convertidas
(colher de sopa = 15 ml, xícara = 240 ml). Quando a receita mede em volume e o insumo é comprado
por peso (ou em unidades), o app avisa e pede a equivalência (ex.: manteiga ≈ 0,9 g/ml).

O conteúdo informado à mão fica guardado no insumo e vale para as próximas compras do mesmo produto.
O preço pode ser o da **última compra**, a **média ponderada** ou um **preço manual**.

Depois dos ingredientes, o app pede:
- **Mão de obra**: aditiva (soma R$) ou multiplicativa (% sobre o custo dos ingredientes);
- **Margem de lucro**: aditiva (soma R$) ou multiplicativa (% sobre o custo com mão de obra).

O resultado mostra custo total, preço de venda, preço por porção e margem sobre o preço.

## Notas sem QR code

- **Chave de acesso** (44 números impressos no cupom): o app lê o estado, CNPJ, número e mês.
  No celular, abre a consulta da SEFAZ com a chave já copiada; você resolve a verificação e o app lê a nota.
  No computador, abre o site para baixar o XML. Nos dois, dá para lançar manualmente com os dados da chave.
- **XML**: o QR code não dá acesso ao XML (a SEFAZ só entrega com certificado digital), mas o app importa
  arquivos `.xml` de NF-e/NFC-e, como os que fornecedores enviam por e-mail, com desconto por item.
- **Manual**: feira, compras na internet, cursos, entregas.

## Vários aparelhos

Todos os aparelhos pareados ficam com os mesmos dados (notas, receitas, insumos, vendas, configurações):

- **Com um computador**: pareie cada celular com ele. O computador fica sempre disponível e repassa
  as alterações: o que um celular lança aparece nos outros na próxima sincronização.
- **Sem computador**: pareie os celulares entre si. Eles sincronizam quando os dois estão com o app aberto
  no mesmo Wi-Fi. Dá para combinar: celulares pareados entre si e também com o computador.
- O celular sincroniza ao abrir o app, alguns segundos depois de cada alteração e a cada minuto com o app aberto.
- A sincronização é só pela rede local (mesmo Wi-Fi). Para acessar de fora de casa seria preciso um servidor na internet.

## Sincronização e segurança

- Os aparelhos se encontram na rede (porta UDP 47823) e conversam na porta 47822.
- No primeiro pareamento, os dois trocam chaves (ECDH P-256) e o aparelho procurado mostra um **código de 6 dígitos**
  calculado a partir delas; digitado no outro, garante que não há ninguém no meio. Código errado cancela o pareamento.
- A chave fica guardada nos dois aparelhos ("lembrar"). Depois disso, todo o tráfego vai cifrado e autenticado
  (AES-256-GCM). Aparelhos não pareados são recusados. Dá para *Esquecer* um aparelho a qualquer momento.
- Se o mesmo registro for alterado nos dois aparelhos antes de sincronizar, vale a alteração mais recente.

## Estrutura do código

```
core/          Kotlin puro, sem dependências — usado pelo Android e pelo computador
  Banco.kt         banco de dados em JSON, com controle de versão para sincronizar
  Unidades.kt      detecção de embalagem e conversão de medidas
  Custos.kt        insumos, preço tabelado e custo de receitas
  Painel.kt        indicadores e "Principais descobertas"
  Importadores.kt  página da SEFAZ, XML da NF-e, planilha antiga, chave de acesso
  Xlsx.kt          gerador de Excel (estilos, gráficos, tabela dinâmica)
  Relatorio.kt     o Excel no formato da planilha de controle
  Sync.kt          descoberta, pareamento, criptografia e sincronização
  Api.kt           métodos chamados pela interface
shared-assets/web/ interface (HTML/CSS/JS), a mesma no celular e no computador
app/           Android: WebView, leitor de QR, consulta na SEFAZ, salvar arquivos
desktop/       computador: servidor local da interface + servidor de sincronização
```

## Limitações

- O layout da página da SEFAZ varia por estado. O leitor reconhece o layout padrão do portal da NFC-e
  e tabelas genéricas; se o seu estado não funcionar, o ajuste é só em `shared-assets/web/extrator.js`.
- Os endereços de consulta por chave de cada estado são os publicados pelas SEFAZ e podem mudar;
  o campo é editável e o endereço correto vem impresso no cupom.
