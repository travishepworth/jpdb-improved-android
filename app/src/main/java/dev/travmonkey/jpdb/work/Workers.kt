package dev.travmonkey.jpdb.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.travmonkey.jpdb.App
import dev.travmonkey.jpdb.Notifications
import dev.travmonkey.jpdb.data.ApiError
import dev.travmonkey.jpdb.data.AuthError
import dev.travmonkey.jpdb.data.FlushResult
import java.util.concurrent.TimeUnit

private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/** Sends queued grades, even if the app has been closed. Refreshes the queue afterwards if it's stale. */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        return when (app.repo.flush()) {
            FlushResult.Done -> {
                if (System.currentTimeMillis() - app.repo.state.value.lastSync > 30 * 60_000L) {
                    runCatching { app.repo.sync() }.onFailure { Log.w("jpdb", "post-upload sync failed", it) }
                }
                Result.success()
            }
            FlushResult.Retry, FlushResult.Offline -> Result.retry()
            FlushResult.Auth -> {
                Notifications.loginNeeded(app)
                Result.failure()
            }
        }
    }

    companion object {
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(connected)
                .setInitialDelay(10, TimeUnit.SECONDS) // past the undo window; the in-app loop normally gets there first
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("outbox", ExistingWorkPolicy.KEEP, req)
        }
    }
}

/** Periodic: upload anything pending, then pull a fresh due queue so offline reviews are always primed. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        when (app.repo.flush()) {
            FlushResult.Auth -> Notifications.loginNeeded(app)
            FlushResult.Retry, FlushResult.Offline -> return Result.retry()
            FlushResult.Done -> {}
        }
        return try {
            app.repo.sync()
            Result.success()
        } catch (e: ApiError) {
            Log.w("jpdb", "background sync failed", e)
            if (e.code.startsWith("http_5") || e.code == "too_many_requests") Result.retry() else Result.failure()
        } catch (e: AuthError) {
            Result.failure()
        } catch (e: Exception) {
            Log.w("jpdb", "background sync failed", e)
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context, hours: Int) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(hours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
