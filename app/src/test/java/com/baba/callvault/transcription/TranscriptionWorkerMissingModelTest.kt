// SPDX-License-Identifier: GPL-3.0-or-later
package com.baba.callvault.transcription

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.baba.callvault.data.transcripts.db.TranscriptDatabase
import com.baba.callvault.data.transcripts.db.TranscriptEntry
import com.baba.callvault.data.transcripts.db.TranscriptState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TranscriptionWorkerMissingModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun worker(name: String) = TestListenableWorkerBuilder<TranscriptionWorker>(context)
        .setInputData(workDataOf(TranscriptionWorker.KEY_DISPLAY_NAME to name,
            TranscriptionWorker.KEY_USER_REQUESTED to true)).build()

    @Test fun missing_model_finishes_the_request_and_marks_the_row_failed() = runBlocking {
        val name = "worker-missing-model.ogg"
        val dao = TranscriptDatabase.get(context).transcriptDao()
        dao.upsertTranscript(TranscriptEntry(name, TranscriptState.QUEUED))
        val result = worker(name).doWork()
        val message = context.getString(com.baba.callvault.R.string.transcription_error_model_missing)
        assertEquals(ListenableWorker.Result.success(workDataOf(TranscriptionWorker.KEY_ERROR to message)), result)
        assertEquals(TranscriptState.FAILED, dao.findTranscript(name)!!.state)
        assertEquals(message, dao.findTranscript(name)!!.errorMessage)
    }

    @Test fun missing_model_cannot_replace_an_already_finished_transcript_with_failure() = runBlocking {
        val name = "worker-finished-model.ogg"
        val dao = TranscriptDatabase.get(context).transcriptDao()
        dao.upsertTranscript(TranscriptEntry(name, TranscriptState.DONE, modelId = "earlier-model"))
        assertEquals(ListenableWorker.Result.success(), worker(name).doWork())
        assertEquals(TranscriptState.DONE, dao.findTranscript(name)!!.state)
        assertEquals("earlier-model", dao.findTranscript(name)!!.modelId)
    }

    @Test fun a_queued_request_keeps_its_model_when_settings_change() = runBlocking {
        val name = "worker-pinned-model.ogg"
        com.baba.callvault.data.AppPreferences(context).setTranscriptionModelId("large-v3-turbo-q8_0")
        val worker = TestListenableWorkerBuilder<TranscriptionWorker>(context)
            .setInputData(workDataOf(TranscriptionWorker.KEY_DISPLAY_NAME to name,
                TranscriptionWorker.KEY_MODEL_ID to "small-q5_1",
                TranscriptionWorker.KEY_USER_REQUESTED to true)).build()
        worker.doWork()
        assertEquals("small-q5_1", TranscriptDatabase.get(context).transcriptDao().findTranscript(name)!!.modelId)
    }
}
