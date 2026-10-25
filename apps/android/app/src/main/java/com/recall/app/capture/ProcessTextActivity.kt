package com.recall.app.capture

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.recall.app.MainActivity
import com.recall.app.Session
import kotlinx.coroutines.launch

/** Handles ACTION_PROCESS_TEXT ("Save to Recall" in the text selection menu). */
class ProcessTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            finish()
            return
        }

        if (!Session.isUnlocked()) {
            PendingShare.set(PendingShare.Payload("text/plain", text, null, null))
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            Toast.makeText(this, "Unlock Recall to save this capture.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val saved = CaptureSaver.save(this@ProcessTextActivity, payloadForText(text, null))
            Toast.makeText(
                this@ProcessTextActivity,
                if (saved) "Saved to Recall" else "Could not save",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }
}
