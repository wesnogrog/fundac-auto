package br.com.surto.fundacauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast
import java.text.Normalizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutoService : AccessibilityService() {

    private class Stopped : RuntimeException()
    private class Fail(msg: String) : RuntimeException(msg)

    private val ui = Handler(Looper.getMainLooper())
    private var button: Button? = null
    @Volatile private var targetPkg: String? = null

    companion object {
        @Volatile var instance: AutoService? = null
        private val DIAC = Regex("\\p{Mn}+")
        private val SPACES = Regex("\\s+")
        private val PREFIX = Regex("^[^:]{1,25}:\\s*")

        fun norm(s: String): String {
            val d = Normalizer.normalize(s, Normalizer.Form.NFD)
            val t = DIAC.replace(d, "").lowercase()
                .replace('–', '-').replace('—', '-').replace('‑', '-')
            return SPACES.replace(t, " ").trim()
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onServiceConnected() {
        instance = this
        showButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        State.stopRequested = true
        try {
            button?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        } catch (_: Exception) {
        }
        button = null
        instance = null
        return super.onUnbind(intent)
    }

    // ---------------------------------------------------------------- botão flutuante

    private fun showButton() {
        if (button != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = Button(this)
        b.textSize = 12f
        b.isAllCaps = false
        b.setOnClickListener { onButtonTap() }
        b.setOnLongClickListener { dumpScreen(); true }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        wm.addView(b, lp)
        button = b
        refreshButton()
    }

    fun refreshButton() {
        ui.post {
            val b = button ?: return@post
            val total = State.items.size
            b.text = when {
                State.running -> "⏸ PARAR ${State.index}/$total"
                total == 0 -> "▶ sem lista"
                State.index >= total -> "✔ fim ${State.index}/$total"
                else -> "▶ LANÇAR ${State.index}/$total"
            }
        }
    }

    private fun toast(m: String) {
        ui.post { Toast.makeText(applicationContext, m, Toast.LENGTH_LONG).show() }
    }

    private fun onButtonTap() {
        if (State.running) {
            State.stopRequested = true
            toast("Parando…")
            return
        }
        if (State.items.isEmpty()) {
            toast("Abra o app Fundac Auto e leia o relatório primeiro.")
            return
        }
        if (State.index >= State.items.size) {
            toast("Lista concluída. Leia um novo relatório.")
            return
        }
        State.running = true
        State.stopRequested = false
        targetPkg = null
        refreshButton()
        Thread { runLoop() }.apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- laço principal

    private fun runLoop() {
        val msg = try {
            while (State.index < State.items.size) {
                if (State.stopRequested) throw Stopped()
                val item = State.items[State.index]
                State.status = "Lançando ${State.index + 1}/${State.items.size}: ${item.name}"
                refreshButton()
                val skip = processItem(item)
                if (skip == null) State.done.add("${item.name} ${item.price}")
                else State.skipped.add("${item.name} — $skip")
                State.index++
                refreshButton()
                Thread.sleep(300)
            }
            if (onSearch()) performGlobalAction(GLOBAL_ACTION_BACK)
            "Terminou: ${State.done.size} lançados, ${State.skipped.size} não lançados."
        } catch (e: Stopped) {
            "Parado por você no item ${State.index + 1}."
        } catch (e: Fail) {
            "Parei no item ${State.index + 1}: ${e.message}"
        } catch (e: Exception) {
            "Erro inesperado: ${e.javaClass.simpleName} ${e.message}"
        }
        State.running = false
        State.status = msg
        refreshButton()
        toast(msg)
    }

    /** Devolve null se lançou; ou o motivo de ter pulado o produto. */
    private fun processItem(item: Item): String? {
        dismissWarning(0)

        if (targetPkg == null) {
            if (!waitUntil(6000) { onSearch() || findAdd() != null })
                throw Fail("não achei a tela da loja. Deixe a loja aberta na aba PRODUTOS e toque no botão de novo.")
            targetPkg = root()?.packageName?.toString()
        }

        // 1) chegar na tela de busca de produtos
        if (!onSearch()) {
            if (!waitUntil(6000) { onSearch() || findAdd() != null })
                throw Fail("não achei o botão + Adicionar.")
            if (!onSearch()) {
                val add = findAdd() ?: throw Fail("não achei o botão + Adicionar.")
                if (!clickAndWait(add) { onSearch() })
                    throw Fail("toquei em + Adicionar, mas a busca de produtos não abriu.")
            }
        }

        // 2) colar o nome na busca
        val field = waitFor(4000) { root()?.let { r -> findFirst(r) { it.isEditable && it.isVisibleToUser } } }
            ?: throw Fail("não achei o campo de busca.")
        val nameN = norm(item.name)
        if (!fill(field, item.name) { norm(it) == nameN })
            throw Fail("não consegui colar o nome do produto na busca.")

        // 3) escolher o resultado (ignora "pack")
        Thread.sleep(700)
        var cands: List<AccessibilityNodeInfo> = emptyList()
        var lastKey = ""
        var stable = 0
        val end = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < end) {
            if (State.stopRequested) throw Stopped()
            val r = root()
            val list = if (r == null) emptyList() else
                findAll(r) { !it.isEditable && it.isVisibleToUser && norm(txt(it)).contains(nameN) }
                    .distinctBy { norm(txt(it)) }
            val key = list.joinToString("|") { txt(it) }
            if (list.isNotEmpty()) cands = list
            if (list.isNotEmpty() && key == lastKey) {
                stable++
                if (stable >= 2) break
            } else {
                stable = 0
                lastKey = key
            }
            Thread.sleep(350)
        }
        if (cands.isEmpty()) return "não apareceu na busca"
        val nonPack = cands.filter { !norm(txt(it)).contains("pack") }
        if (nonPack.isEmpty()) return "só apareceu versão pack"
        val chosen = when {
            nonPack.size == 1 -> nonPack[0]
            else -> nonPack.filter { PREFIX.replace(norm(txt(it)), "") == nameN }.singleOrNull()
        } ?: return "vários resultados (${nonPack.size}) sem pack"

        if (!clickAndWait(chosen) { onDialog() })
            throw Fail("toquei no produto, mas a tela de valor não abriu.")

        // 4) valor
        val vf = waitFor(3000) { findValueField() } ?: throw Fail("não achei o campo Valor.")
        val want = item.digits.trimStart('0')
        val ok = fill(vf, item.digits) { it.filter(Char::isDigit).trimStart('0') == want }
        if (!ok) {
            root()?.let { r -> findFirst(r) { txt(it).trim().equals("Cancelar", true) } }?.let { clickNode(it) }
            Thread.sleep(500)
            return "o campo Valor não aceitou ${item.price}"
        }

        // 5) salvar
        val save = waitFor(2000) { root()?.let { r -> findFirst(r) { txt(it).trim().equals("Salvar", true) } } }
            ?: throw Fail("não achei o botão Salvar.")
        if (!clickAndWait(save) { !onDialog() })
            throw Fail("toquei em Salvar, mas a tela de valor não fechou.")

        // 6) aviso "gravado com advertência" -> OK
        dismissWarning(2500)
        return null
    }

    private fun dismissWarning(waitMs: Long) {
        val end = SystemClock.uptimeMillis() + waitMs
        do {
            val r = root()
            if (r != null) {
                val warn = findFirst(r) {
                    val t = norm(txt(it))
                    t.contains("advertencia") || t.contains("possivelmente discrepante")
                }
                if (warn != null) {
                    val ok = findFirst(r) { txt(it).trim().equals("OK", true) && it.isVisibleToUser }
                    if (ok != null) {
                        clickAndWait(ok) {
                            val r2 = root()
                            r2 == null || findFirst(r2) { n -> norm(txt(n)).contains("advertencia") } == null
                        }
                        Thread.sleep(300)
                        return
                    }
                }
            }
            if (State.stopRequested) throw Stopped()
            if (waitMs > 0) Thread.sleep(250)
        } while (SystemClock.uptimeMillis() < end)
    }

    // ---------------------------------------------------------------- telas

    private fun root(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow ?: return null
        val p = r.packageName?.toString()
        if (p == packageName) return null
        val t = targetPkg
        if (t != null && p != t) return null
        return r
    }

    private fun findAdd(): AccessibilityNodeInfo? =
        root()?.let { r -> findFirst(r) { txt(it).replace(" ", "").equals("+Adicionar", true) && it.isVisibleToUser } }

    private fun onSearch(): Boolean =
        root()?.let { r -> findFirst(r) { txt(it).trim().equals("Filtro Livre", true) } } != null

    private fun onDialog(): Boolean {
        val r = root() ?: return false
        return findFirst(r) { txt(it).trim().equals("Salvar", true) } != null &&
            findFirst(r) { it.isEditable } != null
    }

    private fun findValueField(): AccessibilityNodeInfo? {
        val r = root() ?: return null
        val eds = findAll(r) { it.isEditable && it.isVisibleToUser }
        if (eds.isEmpty()) return null
        val half = resources.displayMetrics.widthPixels / 2
        fun b(n: AccessibilityNodeInfo): Rect {
            val x = Rect()
            n.getBoundsInScreen(x)
            return x
        }
        val left = eds.filter { b(it).centerX() < half }.ifEmpty { eds }
        return left.minByOrNull { b(it).top }
    }

    // ---------------------------------------------------------------- utilidades de nós

    private fun txt(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription)?.toString().orEmpty()

    private fun findAll(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun go(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) go(n.getChild(i), depth + 1)
        }
        go(root, 0)
        return out
    }

    private fun findFirst(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        findAll(root, pred).firstOrNull()

    private fun <T> waitFor(ms: Long, f: () -> T?): T? {
        val end = SystemClock.uptimeMillis() + ms
        while (true) {
            if (State.stopRequested) throw Stopped()
            val v = f()
            if (v != null) return v
            if (SystemClock.uptimeMillis() >= end) return null
            Thread.sleep(250)
        }
    }

    private fun waitUntil(ms: Long, c: () -> Boolean): Boolean = waitFor(ms) { if (c()) true else null } ?: false

    private fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && depth < 6) {
            if (c.isClickable && c.isEnabled && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
            depth++
        }
        return tapNode(n)
    }

    private fun tapNode(n: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        var ok = false
        val latch = CountDownLatch(1)
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        latch.await(2, TimeUnit.SECONDS)
        return ok
    }

    /** Clica; se a tela esperada não aparecer em 3s, tenta de novo com um toque por coordenada. */
    private fun clickAndWait(n: AccessibilityNodeInfo, cond: () -> Boolean): Boolean {
        clickNode(n)
        if (waitUntil(3000, cond)) return true
        tapNode(n)
        return waitUntil(3000, cond)
    }

    private fun setText(n: AccessibilityNodeInfo, s: String): Boolean {
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    private fun setClip(t: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("fundac", t))
    }

    /** Limpa o campo, cola o texto (como você faz à mão) e confere; se falhar, tenta escrever direto. */
    private fun fill(field: AccessibilityNodeInfo, text: String, ok: (String) -> Boolean): Boolean {
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(250)
        setText(field, "")
        Thread.sleep(200)
        setClip(text)
        field.refresh()
        field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Thread.sleep(500)
        field.refresh()
        if (ok(field.text?.toString().orEmpty())) return true
        setText(field, text)
        Thread.sleep(500)
        field.refresh()
        return ok(field.text?.toString().orEmpty())
    }

    // ---------------------------------------------------------------- diagnóstico (segurar o botão)

    private fun dumpScreen() {
        Thread {
            val r = rootInActiveWindow
            val sb = StringBuilder()
            sb.append("pacote: ").append(r?.packageName).append('\n')
            var count = 0
            fun go(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || count > 400 || depth > 40) return
                count++
                val b = Rect()
                n.getBoundsInScreen(b)
                sb.append("  ".repeat(depth.coerceAtMost(10)))
                    .append(n.className).append(" | txt=").append(n.text)
                    .append(" | desc=").append(n.contentDescription)
                    .append(" | ").append(if (n.isEditable) "E" else "").append(if (n.isClickable) "C" else "")
                    .append(" | ").append(b.flattenToString()).append('\n')
                for (i in 0 until n.childCount) go(n.getChild(i), depth + 1)
            }
            go(r, 0)
            ui.post {
                setClip(sb.toString())
                Toast.makeText(applicationContext, "Diagnóstico copiado ($count itens). Cole no chat do Claude.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
