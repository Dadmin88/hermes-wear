package io.github.hermeswear

import android.app.Activity
import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var settings: PhoneSettings
    private lateinit var address: EditText
    private lateinit var secret: EditText
    private lateinit var pairCode: EditText
    private lateinit var status: TextView

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 15f): TextView =
        TextView(this).apply {
            this.text = text; textSize = size
            setTextColor(Color.rgb(218, 228, 227))
            setPadding(0, dp(10), 0, dp(9))
        }
    private fun button(text: String): Button = Button(this).apply {
        this.text = text
        setAllCaps(false)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(116, 225, 180))
        setTextColor(Color.rgb(15, 28, 24))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = PhoneSettings(this)
        Diagnostics.flush(applicationContext)
        window.statusBarColor = Color.rgb(12, 20, 19)
        window.navigationBarColor = Color.rgb(12, 20, 19)
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 20, 19))
            setPadding(dp(24), dp(26), dp(24), dp(24))
        }
        view.addView(label("HERMES  /  WEAR OS  1.0.0-rc1", 25f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(140, 239, 192))
        })
        view.addView(label("Your watch can connect directly to your always-on Hermes relay. This companion handles pairing and Bluetooth fallback.").apply { textSize = 16f })
        view.addView(label("PAIR AUTOMATICALLY", 17f).apply {
            setTextColor(Color.rgb(140, 239, 192))
            typeface = Typeface.DEFAULT_BOLD
        })
        view.addView(label("Enter the short one-time code generated on the relay host. No long token to copy."))
        pairCode = EditText(this).apply {
            setSingleLine(true)
            hint = "XXXX-XXXX-XXXX"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        view.addView(pairCode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        val autoPair = button("Pair automatically")
        autoPair.setOnClickListener {
            val code = pairCode.text.toString()
            val endpoint=address.text.toString().trim().trimEnd('/')
            if(!settings.validUrl(endpoint)) {status.text="Enter the relay HTTPS URL";return@setOnClickListener}
            settings.url=endpoint
            status.text = "Pairing securely with Hermes..."
            autoPair.isEnabled = false
            Thread {
                val response = RelayHttp(applicationContext).pairWithCode(code)
                runOnUiThread {
                    autoPair.isEnabled = true
                    if (response.optBoolean("ok")) {
                        address.setText(settings.url)
                        secret.setText(settings.token)
                        pairCode.setText("")
                        status.text = "Authenticated. Syncing watch connection."
                        ConnectionSync.all(applicationContext)
                    } else {
                        status.text = response.optString("error", "Pairing failed")
                    }
                }
            }.start()
        }
        view.addView(autoPair)
        view.addView(label("MANUAL CONNECTION (OPTIONAL)", 16f).apply {
            setTextColor(Color.rgb(140, 239, 192))
        })
        view.addView(label("Relay HTTPS URL"))
        address = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://relay.example.com/hermes-wear"
            setText(settings.url)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        view.addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        view.addView(label("Device access token (encrypted on each paired device)"))
        secret = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Device token"
            setText(settings.token)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        view.addView(secret, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        status = label("Both apps must be installed and signed with the same key.")
        view.addView(status)
        val save = button("Save connection")
        save.setOnClickListener {
            val url = address.text.toString().trim().trimEnd('/')
            val token = secret.text.toString().trim()
            if (!settings.validUrl(url) || token.length < 24) {
                status.text = "Enter a valid HTTPS URL and a token with at least 24 characters."
                return@setOnClickListener
            }
            settings.url = url
            settings.token = token
            status.text = "Connection settings saved; syncing watch."
            ConnectionSync.all(applicationContext)
        }
        view.addView(save)
        val test = button("Test relay")
        test.setOnClickListener {
            status.text = "Connecting..."
            Thread {
                val result = RelayHttp(applicationContext).test()
                runOnUiThread {
                    status.text = if (result.optBoolean("ok")) "Relay reachable. Ready for watch requests."
                    else "Connection failed: " + result.optString("error", "Cannot reach relay")
                }
            }.start()
        }
        view.addView(test)
        view.addView(button("Sync connection to watch").apply {
            setOnClickListener {ConnectionSync.all(applicationContext);status.text="Connection sent to connected watch"}
        })
        view.addView(label("Credentials are encrypted using Android Keystore. The backend continues accepted tasks when this app closes.", 14f))
        setContentView(ScrollView(this).apply { addView(view) })
    }
}
