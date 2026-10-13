package br.com.surto.fundacauto

/**
 * Lê o texto do relatório e devolve só (descrição do produto, valor).
 * Aceita "NOME 9,00", "NOME | 9,00", "NOME: 9,00" e também nome numa linha e valor na linha seguinte.
 * Linhas sem valor (nome da loja, "CEP", títulos como "Lata - 350 ml") são ignoradas.
 */
object Parser {
    private val priceRe = Regex("""(?<![\d.,])(?:R\$\s*)?(\d{1,3}(?:\.\d{3})+|\d+),(\d{2})\s*$""")
    private val bulletRe = Regex("""^[-•·]\s+""")
    private val spaces = Regex("""\s+""")
    private val noise = setOf("produto", "produtos", "preco", "preço", "valor", "item", "nome", "descricao", "descrição", "cep")

    fun parse(text: String): List<Item> {
        val out = ArrayList<Item>()
        var pending: String? = null
        for (raw in text.lines()) {
            var line = raw.replace('|', '\t').replace("*", "").replace("`", "").replace(' ', ' ').trim()
            line = bulletRe.replace(line, "").trim()
            if (line.isEmpty()) continue

            val m = priceRe.find(line)
            if (m == null) {
                pending = line   // nome sem valor na mesma linha (ou título/loja, que será descartado)
                continue
            }
            val before = line.substring(0, m.range.first)
                .trim()
                .trimEnd(':', '–', '—', '-', '|', '\t', ' ', '.', '·', '→', '=')
            val cents = m.groupValues[1].replace(".", "").toLong() * 100 + m.groupValues[2].toLong()
            val name = (if (before.isNotEmpty()) before else pending)?.let { spaces.replace(it, " ").trim() }
            pending = null
            if (name == null || name.isEmpty()) continue
            if (name.lowercase() in noise) continue
            if (name.none { it.isLetter() }) continue
            out.add(Item(name, cents.toInt()))
        }
        return out
    }
}
