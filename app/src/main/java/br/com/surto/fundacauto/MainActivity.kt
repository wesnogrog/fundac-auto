package br.com.surto.fundacauto

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var preview: TextView
    private lateinit var statusView: TextView
    private lateinit var accView: TextView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        fun tv(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = s
            textSize = size
            setPadding(0, dp(6), 0, dp(6))
            if (bold) setTypeface(null, Typeface.BOLD)
        }

        fun bt(s: String, f: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { f() }
        }

        col.addView(tv("Fundac Auto", 24f, true))
        accView = tv("")
        col.addView(accView)
        col.addView(bt("1) Ativar acessibilidade") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(bt("Android bloqueou? Liberar configurações restritas") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        col.addView(tv("2) Cole o relatório (só os produtos da loja que está aberta na Fundac):"))
        input = EditText(this).apply {
            minLines = 6
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        col.addView(input)
        col.addView(bt("Colar da área de transferência") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            input.setText(t)
        })
        col.addView(bt("Ler relatório") { load() })
        preview = tv("")
        col.addView(preview)

        col.addView(tv("3) Abra a loja no app da Fundac, aba PRODUTOS, e toque no botão flutuante ▶ (lado direito, abaixo do meio da tela). Para parar, toque nele de novo. Segurando o botão por 2 segundos, o app copia um diagnóstico. Se falhar, ele também copia o diagnóstico sozinho: cole no chat do Claude."))
        statusView = tv("")
        col.addView(statusView)
        col.addView(bt("Copiar lista de não lançados") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("nao-lancados", State.skipped.joinToString("\n")))
            Toast.makeText(this, "Copiado.", Toast.LENGTH_SHORT).show()
        })
        col.addView(bt("Zerar tudo") {
            if (State.running) {
                State.stopRequested = true
                Toast.makeText(this, "Parando… toque de novo depois.", Toast.LENGTH_SHORT).show()
            } else {
                State.reset(emptyList())
                preview.text = ""
                AutoService.instance?.refreshButton()
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun load() {
        if (State.running) {
            Toast.makeText(this, "Pare o lançamento antes de ler outro relatório.", Toast.LENGTH_LONG).show()
            return
        }
        val items = Parser.parse(input.text.toString())
        State.reset(items)
        AutoService.instance?.refreshButton()
        if (items.isEmpty()) {
            preview.text = "Não achei nenhum produto com valor (ex.: 9,00). Confira o texto colado."
            return
        }
        val dup = items.groupBy { AutoService.norm(it.name) }.filter { it.value.size > 1 }.values
            .joinToString("\n") { "• ${it[0].name}" }
        preview.text = "${items.size} produtos lidos:\n" +
            items.joinToString("\n") { "${it.name}  →  ${it.price}" } +
            if (dup.isNotEmpty()) "\n\nATENÇÃO, nomes repetidos (confira se colou mais de uma loja):\n$dup" else ""
    }

    private fun refresh() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        accView.text = if (enabled) "Acessibilidade: ATIVA ✔" else "Acessibilidade: DESLIGADA ✘"
        val sk = if (State.skipped.isEmpty()) "" else "\n\nNão lançados:\n" + State.skipped.joinToString("\n")
        statusView.text = "${State.status}\nLançados: ${State.done.size} | Não lançados: ${State.skipped.size}$sk"
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
    }
}
