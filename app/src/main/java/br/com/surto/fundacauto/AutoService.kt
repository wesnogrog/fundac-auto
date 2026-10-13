package br.com.surto.fundacauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * O app da Fundac desenha tudo numa única View (o Android não enxerga botões nem textos).
 * Por isso este serviço: tira print da tela, lê o texto com OCR (ML Kit), toca nas posições
 * encontradas e cola o texto usando a área de transferência do teclado.
 */
class AutoService : AccessibilityService() {

    private class Stopped : RuntimeException()
    private class Fail(msg: String) : RuntimeException(msg)

    private class Row(val text: String, val box: Rect) {
        val k: String = AutoService.keyOf(text)
    }

    private class Shot(val w: Int, val h: Int, val lines: List<Row>, val blocks: List<Row>)

    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var button: Button? = null
    private var lastGrab = 0L
    @Volatile private var lastShot: Shot? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    companion object {
        const val FUNDAC = "com.br.Fundacte"
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

        /** Chave tolerante a erros de leitura (l/1/i, 0/o, pontuação). */
        fun keyOf(s: String): String =
            norm(s).filter { it.isLetterOrDigit() }.replace('l', 'i').replace('1', 'i').replace('0', 'o')
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
        } catch (e: Exception) {
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
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(24, 8, 24, 8)
        b.alpha = 0.9f
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
        lp.y = 700   // abaixo do meio da tela, longe dos botões que o app toca
        wm.addView(b, lp)
        button = b
        refreshButton()
    }

    fun refreshButton() {
        ui.post {
            val b = button ?: return@post
            val total = State.items.size
            b.text = when {
                State.running -> "⏸ ${State.index}/$total"
                total == 0 -> "▶ -"
                State.index >= total -> "✔ $total"
                else -> "▶ ${State.index}/$total"
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
        refreshButton()
        Thread { runLoop() }.apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- laço principal

    private fun runLoop() {
        val msg = try {
            guard()
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
                nap(300)
            }
            try {
                val s = snap()
                if (s.isSearch()) performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (e: Exception) {
            }
            "Terminou: ${State.done.size} lançados, ${State.skipped.size} não lançados."
        } catch (e: Stopped) {
            "Parado por você no item ${State.index + 1}."
        } catch (e: Fail) {
            copyDiag(e.message.orEmpty())
            "Parei no item ${State.index + 1}: ${e.message} (diagnóstico copiado: cole no chat do Claude)"
        } catch (e: Exception) {
            copyDiag("${e.javaClass.simpleName} ${e.message}")
            "Erro inesperado: ${e.javaClass.simpleName} ${e.message} (diagnóstico copiado)"
        }
        State.running = false
        State.status = msg
        refreshButton()
        toast(msg)
    }

    /** Devolve null se lançou; ou o motivo de ter pulado o produto. */
    private fun processItem(item: Item): String? {
        val s = ensureSearch()
        val fx = s.w * 0.5f
        val fy = s.h * 0.248f
        val nameKey = keyOf(item.name)

        // apaga texto antigo da busca (botão X)
        val hasText = s.lines.any {
            abs(it.box.centerY() - fy) < s.h * 0.02f &&
                it.box.centerX() > s.w * 0.12f && it.box.centerX() < s.w * 0.88f
        }
        if (hasText) {
            tapAt(s.w * 0.93f, fy)
            nap(500)
        }

        // cola o nome na busca
        if (!pasteInto(item.name, fx, fy))
            throw Fail("não consegui colocar o nome do produto na busca (o teclado não deixou colar).")
        nap(900)

        // lê os resultados até ficarem estáveis
        var cands: List<Row> = emptyList()
        var lastKeys = ""
        val end = SystemClock.uptimeMillis() + 6000
        while (SystemClock.uptimeMillis() < end) {
            val sh = snap()
            val list = sh.blocks.filter {
                it.box.centerY() > fy + sh.h * 0.03f && it.box.centerY() < sh.h * 0.86f && it.k.contains(nameKey)
            }
            val keys = list.joinToString("|") { it.k }
            if (list.isNotEmpty()) cands = list
            if (list.isNotEmpty() && keys == lastKeys) break
            lastKeys = keys
            nap(300)
        }
        if (cands.isEmpty()) return "não apareceu na busca"

        val nonPack = cands.filter { !it.k.contains("pack") }
        if (nonPack.isEmpty()) return "só apareceu versão pack"
        val chosen: Row = when {
            nonPack.size == 1 -> nonPack[0]
            else -> nonPack.filter { keyOf(PREFIX.replace(norm(it.text), "")) == nameKey }.singleOrNull()
        } ?: return "vários resultados (${nonPack.size}) sem pack"

        tapRow(chosen)
        val d = waitShot(7000) { it.isDialog() }
            ?: throw Fail("toquei no produto, mas a tela de valor não abriu.")

        // valor
        val label = d.lines.firstOrNull { it.k == keyOf("valor") && it.box.centerX() < d.w * 0.5f }
        val vx = label?.box?.centerX()?.toFloat() ?: (d.w * 0.22f)
        val vy = (label?.box?.centerY()?.toFloat() ?: (d.h * 0.256f)) + d.h * 0.035f
        tapAt(vx, vy)
        nap(900)
        typeDigits(item.digits, d.w, d.h)
        nap(400)

        val v = snap()
        val read = v.lines
            .filter { it.box.centerX() < v.w * 0.5f && abs(it.box.centerY() - vy) < v.h * 0.045f }
            .joinToString("") { it.text }
            .filter { it.isDigit() }
            .trimStart('0')
        val want = item.digits.trimStart('0')
        if (read.isNotEmpty() && read != want) {
            v.lines.firstOrNull { it.k == keyOf("cancelar") }?.let { tapRow(it) }
            nap(700)
            return "o campo Valor ficou com $read em vez de $want"
        }

        val sv = v.lines.firstOrNull { it.k == keyOf("salvar") } ?: throw Fail("não achei o botão Salvar.")
        tapRow(sv)

        val a = waitShot(7000) { it.isWarn() || it.isSearch() || it.addButton() != null }
            ?: throw Fail("toquei em Salvar, mas a tela de valor não fechou.")
        if (a.isWarn()) dismissWarn(a)
        return null
    }

    /** Garante que está na tela de busca de produtos (abre pelo + Adicionar se preciso). */
    private fun ensureSearch(): Shot {
        repeat(3) {
            val s = waitShot(10000) { it.isWarn() || it.isDialog() || it.isSearch() || it.addButton() != null }
                ?: throw Fail("não reconheci a tela da loja. Deixe a loja aberta na aba PRODUTOS e toque no botão de novo.")
            when {
                s.isWarn() -> dismissWarn(s)
                s.isDialog() -> throw Fail("a tela de valor já estava aberta. Cancele e deixe a loja na aba PRODUTOS.")
                s.isSearch() -> return s
                else -> tapRow(s.addButton()!!)
            }
            nap(600)
        }
        throw Fail("não consegui abrir a busca de produtos.")
    }

    private fun dismissWarn(s: Shot) {
        val ok = s.lines.filter { it.k == keyOf("OK") }.maxByOrNull { it.box.centerY() }
        if (ok != null) tapRow(ok) else tapAt(s.w * 0.81f, s.h * 0.573f)
        nap(700)
    }

    // ---------------------------------------------------------------- telas (por OCR)

    private fun Shot.hasAny(vararg parts: String): Boolean =
        lines.any { l -> parts.any { p -> l.k.contains(keyOf(p)) } }

    private fun Shot.isSearch() = hasAny("filtro livre")
    private fun Shot.isDialog() = hasAny("salvar") && hasAny("cancelar")
    private fun Shot.isWarn() = hasAny("advertencia", "discrepante")
    private fun Shot.addButton(): Row? =
        lines.firstOrNull { it.k.contains(keyOf("adicionar")) && it.box.centerY() < h * 0.2 }

    private fun waitShot(ms: Long, pred: (Shot) -> Boolean): Shot? {
        val end = SystemClock.uptimeMillis() + ms
        while (true) {
            if (State.stopRequested) throw Stopped()
            guard()
            val s = snap()
            if (pred(s)) return s
            if (SystemClock.uptimeMillis() >= end) return null
            nap(150)
        }
    }

    private fun guard() {
        val p = rootInActiveWindow?.packageName?.toString() ?: return
        val ok = p == FUNDAC || p.contains("keyboard") || p.contains("honeyboard") ||
            p.contains("inputmethod") || p.contains("systemui") || p.contains("gboard") || p.contains("latin")
        if (!ok) throw Fail("saí do app da Fundac (apareceu $p). Volte para a loja e toque no botão de novo.")
    }

    // ---------------------------------------------------------------- print + OCR

    private fun grab(): Bitmap? {
        repeat(4) {
            val wait = 450 - (SystemClock.uptimeMillis() - lastGrab)
            if (wait > 0) Thread.sleep(wait)
            val latch = CountDownLatch(1)
            var bmp: Bitmap? = null
            lastGrab = SystemClock.uptimeMillis()
            takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    try {
                        val hb = r.hardwareBuffer
                        bmp = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                    } catch (e: Exception) {
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            })
            latch.await(5, TimeUnit.SECONDS)
            if (bmp != null) return bmp
        }
        return null
    }

    private fun ocr(bmp: Bitmap): Pair<List<Row>, List<Row>> {
        val latch = CountDownLatch(1)
        var res: com.google.mlkit.vision.text.Text? = null
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { res = it; latch.countDown() }
            .addOnFailureListener { latch.countDown() }
        latch.await(15, TimeUnit.SECONDS)
        val lines = ArrayList<Row>()
        val blocks = ArrayList<Row>()
        val r = res
        if (r != null) {
            for (b in r.textBlocks) {
                b.boundingBox?.let { blocks.add(Row(b.text.replace('\n', ' '), it)) }
                for (l in b.lines) l.boundingBox?.let { lines.add(Row(l.text, it)) }
            }
        }
        return Pair(lines, blocks)
    }

    private fun snap(): Shot {
        val bmp = grab() ?: throw Fail("o Android não deixou tirar print da tela.")
        val (lines, blocks) = ocr(bmp)
        val s = Shot(bmp.width, bmp.height, lines, blocks)
        bmp.recycle()
        lastShot = s
        return s
    }

    // ---------------------------------------------------------------- toques e digitação

    private fun nap(ms: Long) {
        Thread.sleep(ms)
        if (State.stopRequested) throw Stopped()
    }

    private fun tapAt(x: Float, y: Float, hold: Long = 70) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, hold)).build()
        val latch = CountDownLatch(1)
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (sent) latch.await(hold / 1000 + 3, TimeUnit.SECONDS)
        nap(250)
    }

    private fun tapRow(r: Row) = tapAt(r.box.exactCenterX(), r.box.exactCenterY())

    private fun setClip(t: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("fundac", t))
    }

    private fun imeWindow(): AccessibilityWindowInfo? =
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

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

    private fun tapNode(n: AccessibilityNodeInfo) {
        val r = Rect()
        n.getBoundsInScreen(r)
        tapAt(r.exactCenterX(), r.exactCenterY())
    }

    private fun clickNode(n: AccessibilityNodeInfo) {
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && depth < 6) {
            if (c.isClickable && c.isEnabled && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            c = c.parent
            depth++
        }
        tapNode(n)
    }

    /** Foca o campo, cola o texto pela área de transferência do teclado e confere por OCR. */
    private fun pasteInto(text: String, fx: Float, fy: Float): Boolean {
        setClip(text)
        tapAt(fx, fy)
        nap(900)
        if (pasteViaKeyboard(text) && fieldShows(text, fy)) return true
        if (pasteViaLongPress(fx, fy) && fieldShows(text, fy)) return true
        return false
    }

    private fun pasteViaKeyboard(text: String): Boolean {
        val w = imeWindow() ?: return false
        val root = w.root ?: return false
        val b = Rect()
        w.getBoundsInScreen(b)
        val icon = findFirst(root) {
            val k = norm(txt(it))
            k.contains("area de transferencia") || k.contains("clipboard") || k.contains("prancheta")
        }
        if (icon != null) clickNode(icon)
        else tapAt(b.left + b.width() * 0.264f, b.top + 65f * b.width() / 1080f)
        nap(800)
        val root2 = imeWindow()?.root ?: return false
        val probe = norm(text).take(10)
        val item = findFirst(root2) { !it.isEditable && norm(txt(it)).contains(probe) } ?: return false
        clickNode(item)
        nap(600)
        return true
    }

    private fun pasteViaLongPress(fx: Float, fy: Float): Boolean {
        tapAt(fx, fy, 800)
        val s = waitShot(2500) { sh -> sh.lines.any { it.k == keyOf("colar") } } ?: return false
        tapRow(s.lines.first { it.k == keyOf("colar") })
        nap(500)
        return true
    }

    private fun fieldShows(text: String, fy: Float): Boolean {
        val s = snap()
        val probe = keyOf(text).take(10)
        return s.lines.any { abs(it.box.centerY() - fy) < s.h * 0.03f && it.k.contains(probe) }
    }

    private fun typeDigits(digits: String, w: Int, h: Int) {
        for (ch in digits) {
            val root = imeWindow()?.root
            val node = root?.let { r -> findFirst(r) { txt(it).trim() == ch.toString() } }
            if (node != null) {
                clickNode(node)
            } else {
                val col = when (ch) {
                    '1', '4', '7' -> 0.134f
                    '2', '5', '8', '0' -> 0.379f
                    else -> 0.620f
                }
                val row = when (ch) {
                    '1', '2', '3' -> 0.673f
                    '4', '5', '6' -> 0.746f
                    '7', '8', '9' -> 0.820f
                    else -> 0.894f
                }
                tapAt(w * col, h * row)
            }
            nap(180)
        }
    }

    // ---------------------------------------------------------------- diagnóstico

    private fun failDiag(msg: String): String {
        val sb = StringBuilder("FALHA: $msg\n")
        lastShot?.let { s ->
            sb.append("tela ${s.w}x${s.h}\nOCR (linhas):\n")
            s.lines.forEach { sb.append(it.text).append("  @").append(it.box.flattenToString()).append('\n') }
        }
        return sb.toString()
    }

    private fun copyDiag(msg: String) {
        val d = failDiag(msg)
        ui.post { setClip(d) }
    }

    /** Segurar o botão flutuante: copia janelas, teclado e texto lido da tela. */
    private fun dumpScreen() {
        Thread {
            val sb = StringBuilder()
            sb.append("pacote ativo: ").append(rootInActiveWindow?.packageName).append('\n')
            try {
                for (w in windows) {
                    val b = Rect()
                    w.getBoundsInScreen(b)
                    sb.append("janela tipo=").append(w.type).append(' ').append(b.flattenToString())
                        .append(" ativa=").append(w.isActive).append('\n')
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        var count = 0
                        fun go(n: AccessibilityNodeInfo?, depth: Int) {
                            if (n == null || count > 150 || depth > 30) return
                            count++
                            val nb = Rect()
                            n.getBoundsInScreen(nb)
                            sb.append("   ").append(n.className).append(" | txt=").append(n.text)
                                .append(" | desc=").append(n.contentDescription)
                                .append(" | ").append(nb.flattenToString()).append('\n')
                            for (i in 0 until n.childCount) go(n.getChild(i), depth + 1)
                        }
                        go(w.root, 0)
                    }
                }
            } catch (e: Exception) {
                sb.append("erro janelas: ").append(e.message).append('\n')
            }
            try {
                val s = snap()
                sb.append("--- OCR ").append(s.w).append('x').append(s.h).append(" ---\n")
                s.lines.forEach { sb.append(it.text).append("  @").append(it.box.flattenToString()).append('\n') }
            } catch (e: Exception) {
                sb.append("OCR falhou: ").append(e.message).append('\n')
            }
            val out = sb.toString()
            ui.post {
                setClip(out)
                Toast.makeText(applicationContext, "Diagnóstico copiado. Cole no chat do Claude.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
