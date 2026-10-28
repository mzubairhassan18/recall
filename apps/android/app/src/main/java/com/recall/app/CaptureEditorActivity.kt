package com.recall.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recall.app.ui.CaptureEditorScreen
import com.recall.app.ui.CaptureEditorViewModel
import com.recall.app.ui.RecallTheme

/** A full-screen editor window, opened from the timeline or archive. */
class CaptureEditorActivity : FragmentActivity() {
    private val viewModel: CaptureEditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val captureId = intent?.getStringExtra(EXTRA_CAPTURE_ID).orEmpty()
        val newCapture = intent?.getBooleanExtra(EXTRA_NEW_CAPTURE, false) == true

        setContent {
            RecallTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(captureId, newCapture) {
                    if (newCapture) viewModel.startNew() else viewModel.load(captureId)
                }
                LaunchedEffect(state.finished) {
                    if (state.finished) finish()
                }
                CaptureEditorScreen(
                    state = state,
                    viewModel = viewModel,
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_CAPTURE_ID = "com.recall.app.extra.CAPTURE_ID"
        const val EXTRA_NEW_CAPTURE = "com.recall.app.extra.NEW_CAPTURE"
    }
}
