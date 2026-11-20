package com.gunluk.widget

import android.app.job.JobParameters
import android.app.job.JobService
import kotlin.concurrent.thread

/** Sistem tarafından düzenli çalıştırılır; hava durumunu ve konumu yeniler */
class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        thread {
            try {
                WidgetProvider.updateAll(app, false)
            } catch (e: Exception) {
                // bir sonraki turda tekrar denenir
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
