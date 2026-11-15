
package com.druk.lmplayground

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import com.druk.llamacpp.InferenceClient
import com.druk.llamacpp.LlamaCpp
import com.druk.lmplayground.data.AppDatabase
import com.druk.lmplayground.data.ChatRepository
import com.druk.lmplayground.data.MemoryRepository
import com.druk.lmplayground.data.SystemPromptRepository
import com.druk.lmplayground.download.DownloadNotificationManager
import com.druk.lmplayground.inference.LlamaService
import com.druk.lmplayground.inference.ProcessUtils
import com.druk.lmplayground.rag.EmbeddingModelManager
import com.druk.lmplayground.rag.RagRepository
import com.druk.lmplayground.storage.LegacyDownloadCleanup
import com.druk.lmplayground.storage.StoragePreferences
import com.druk.lmplayground.storage.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.concurrent.thread

class App : Application() {

    lateinit var inferenceClient: InferenceClient
        private set

    lateinit var llamaCpp: LlamaCpp
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var systemPromptRepository: SystemPromptRepository
        private set

    lateinit var memoryRepository: MemoryRepository
        private set

    lateinit var ragRepository: RagRepository
        private set

    private val applicationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()

        if (ProcessUtils.isLlamaProcess()) return

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                isAppInForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    isAppInForeground = false
                }
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?
            ) {}

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle
            ) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        inferenceClient = InferenceClient(
            appContext = applicationContext,
            serviceComponent = ComponentName(this, LlamaService::class.java),
        )

        inferenceClient.bind()
        llamaCpp = LlamaCpp(inferenceClient)

        DownloadNotificationManager.createChannel(this)

        val appContext = applicationContext

        thread(
            isDaemon = true,
            name = "legacy-download-cleanup"
        ) {
            LegacyDownloadCleanup.run(appContext)
        }

        val database = AppDatabase.getInstance(this)

        chatRepository = ChatRepository(database.chatDao())

        systemPromptRepository =
            SystemPromptRepository(database.systemPromptDao())

        // Repositório de memória permanente do Elias.
        memoryRepository = MemoryRepository(database.memoryDao())

        val embeddingModelManager = EmbeddingModelManager(
            llamaCpp = llamaCpp,
            storageRepository = StorageRepository(
                this,
                StoragePreferences(this)
            ),
            scope = applicationScope,
        )

        ragRepository = RagRepository(
            appContext = applicationContext,
            ragDao = database.ragDao(),
            embeddingManager = embeddingModelManager,
            applicationScope = applicationScope,
        )

        ragRepository.resetStaleIndexing()
    }
}
