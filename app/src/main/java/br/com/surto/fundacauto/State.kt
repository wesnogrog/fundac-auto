package br.com.surto.fundacauto

import java.util.concurrent.CopyOnWriteArrayList

data class Item(val name: String, val cents: Int) {
    /** Dígitos digitados no campo Valor: 3,99 -> "399" */
    val digits: String get() = cents.toString()
    val price: String get() = "${cents / 100},${(cents % 100).toString().padStart(2, '0')}"
}

object State {
    @Volatile var items: List<Item> = emptyList()
    @Volatile var index = 0
    @Volatile var running = false
    @Volatile var stopRequested = false
    @Volatile var status = "Aguardando relatório."
    val done = CopyOnWriteArrayList<String>()
    val skipped = CopyOnWriteArrayList<String>()

    fun reset(newItems: List<Item>) {
        items = newItems
        index = 0
        done.clear()
        skipped.clear()
        stopRequested = false
        status = if (newItems.isEmpty()) "Aguardando relatório."
        else "Pronto: ${newItems.size} produtos. Abra a loja na aba PRODUTOS e toque no botão ▶."
    }
}
