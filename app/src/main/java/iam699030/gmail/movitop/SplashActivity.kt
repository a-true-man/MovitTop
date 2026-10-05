package iam699030.gmail.movitop

import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * First screen shown on launch: an animated MovitTop/Motis lockup while the
 * on-device MOTIS engine boots and loads the offline transit graph into
 * memory in the background (see MotisForegroundService), so MainActivity
 * only appears once routing is actually ready to answer a search.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var logoMark: ImageView
    private lateinit var logoText: TextView
    private lateinit var taglineGroup: LinearLayout
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var loadingText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        logoMark = findViewById(R.id.logoMark)
        logoText = findViewById(R.id.logoText)
        taglineGroup = findViewById(R.id.taglineGroup)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        loadingText = findViewById(R.id.loadingText)

        // Kick off the routing engine as early as possible — the animation
        // below and the graph load then happen concurrently.
        MotisForegroundService.start(this)

        animateIn()

        lifecycleScope.launch {
            val startedAt = System.currentTimeMillis()
            waitForEngineReady(MAX_ENGINE_WAIT_MS)
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < MIN_SPLASH_MS) delay(MIN_SPLASH_MS - elapsed)
            goToMain()
        }
    }

    private fun animateIn() {
        logoMark.alpha = 0f
        logoMark.scaleX = 0.6f
        logoMark.scaleY = 0.6f
        logoText.alpha = 0f
        logoText.translationY = 40f
        taglineGroup.alpha = 0f
        taglineGroup.translationY = 24f
        loadingIndicator.alpha = 0f
        loadingText.alpha = 0f

        logoMark.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        logoText.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(150)
            .setDuration(450)
            .setInterpolator(DecelerateInterpolator())
            .start()

        taglineGroup.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(500)
            .setDuration(400)
            .setInterpolator(DecelerateInterpolator())
            .start()

        loadingIndicator.animate()
            .alpha(1f)
            .setStartDelay(650)
            .setDuration(300)
            .start()

        loadingText.animate()
            .alpha(0.75f)
            .setStartDelay(650)
            .setDuration(300)
            .start()
    }

    /**
     * Polls the local MOTIS server until it answers a request, meaning its
     * (blocking-startup) HTTP loop is up and the graph it serves is already
     * loaded into memory. Returns immediately if there's no graph installed
     * at all — MainActivity's own banner handles that case — and gives up
     * after [timeoutMillis] so a stuck/missing engine never strands the
     * user on the splash screen.
     */
    private suspend fun waitForEngineReady(timeoutMillis: Long) {
        if (!MotisForegroundService.hasOfflineData(this)) return

        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("${MotisBinaryManager.BASE_URL}/api/v1/geocode?text=a")
            .build()

        withTimeoutOrNull(timeoutMillis) {
            while (isActive) {
                val ready = try {
                    withContext(Dispatchers.IO) {
                        client.newCall(request).execute().use { it.isSuccessful }
                    }
                } catch (e: Exception) {
                    false
                }
                if (ready) return@withTimeoutOrNull
                delay(ENGINE_POLL_INTERVAL_MS)
            }
        }
    }

    private fun goToMain() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        private const val MIN_SPLASH_MS = 1400L
        private const val MAX_ENGINE_WAIT_MS = 20_000L
        private const val ENGINE_POLL_INTERVAL_MS = 300L
    }
}
