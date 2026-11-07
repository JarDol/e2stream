package pl.jardol.e2stream

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val p = Prefs(this)

        val host = findViewById<EditText>(R.id.host)
        val httpPort = findViewById<EditText>(R.id.httpPort)
        val streamPort = findViewById<EditText>(R.id.streamPort)
        val user = findViewById<EditText>(R.id.user)
        val pass = findViewById<EditText>(R.id.pass)
        val tunneling = findViewById<CheckBox>(R.id.tunneling)
        val watchdog = findViewById<CheckBox>(R.id.watchdog)
        val stallSec = findViewById<EditText>(R.id.stallSec)
        val bufferMs = findViewById<EditText>(R.id.bufferMs)
        val force50 = findViewById<CheckBox>(R.id.force50)

        host.setText(p.host)
        httpPort.setText(p.httpPort.toString())
        streamPort.setText(p.streamPort.toString())
        user.setText(p.user)
        pass.setText(p.pass)
        tunneling.isChecked = p.tunneling
        watchdog.isChecked = p.watchdog
        stallSec.setText(p.stallSec.toString())
        bufferMs.setText(p.bufferMs.toString())
        force50.isChecked = p.force50Hz

        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }
        findViewById<TextView>(R.id.version).text = "E2Stream $ver"

        findViewById<Button>(R.id.save).setOnClickListener {
            p.host = host.text.toString().trim()
            p.httpPort = httpPort.text.toString().toIntOrNull() ?: 80
            p.streamPort = streamPort.text.toString().toIntOrNull() ?: 8001
            p.user = user.text.toString().trim()
            p.pass = pass.text.toString()
            p.tunneling = tunneling.isChecked
            p.watchdog = watchdog.isChecked
            p.stallSec = (stallSec.text.toString().toIntOrNull() ?: 3).coerceIn(1, 30)
            p.bufferMs = (bufferMs.text.toString().toIntOrNull() ?: 1500).coerceIn(500, 10_000)
            p.force50Hz = force50.isChecked
            p.bump()
            finish()
        }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        host.requestFocus()
    }
}
