package com.homestream.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.Options
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatformLayoutTest {
    private fun render(tv: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("server", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
            .setCurrentModeType(if (tv) Configuration.UI_MODE_TYPE_TELEVISION else Configuration.UI_MODE_TYPE_NORMAL)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            ShadowDialog.getLatestDialog().dismiss()
            // Test-only artwork fetching makes visual previews deterministic and offline.
            // Optional local images may be used for human review; no artwork ships in the app.
            Coil.setImageLoader(ImageLoader.Builder(activity).components {
                add(object : Fetcher.Factory<Any> {
                    override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher = object : Fetcher {
                        override suspend fun fetch(): DrawableResult {
                            val art = System.getProperty("homestream.preview.artwork")
                            val filename = if (data.toString().contains("poster")) "poster.jpg" else "backdrop.jpg"
                            val bitmap = art?.let { BitmapFactory.decodeFile(File(it, filename).path) }
                                ?: Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF303847.toInt()) }
                            return DrawableResult(BitmapDrawable(activity.resources, bitmap), false, DataSource.MEMORY)
                        }
                    }
                })
            }.crossfade(false).build())
            val config = object : ConnectionSettings {
                override val baseUrl = "https://example.invalid"
                override val token = "unused-test-only-token"
                override var profile = "preview"
            }
            MainActivity::class.java.getDeclaredField("api").apply { isAccessible = true }.set(activity, HomeStreamApi(config))
            val content = MainActivity::class.java.getDeclaredField("content").apply { isAccessible = true }.get(activity) as LinearLayout
            content.removeAllViews()
            val fixture = Media(JSONObject("""{"id":"preview","title":"Stranger Things","type":"Series","source":"demo","status":"available","posterUrl":"/poster","backdropUrl":"/backdrop","year":2024,"rating":"TV-14","resolution":"4K UHD","genres":["Mystery","Sci-Fi","Drama"],"overview":"When a young boy vanishes, a small town uncovers a mystery involving secret experiments, terrifying supernatural forces and one extraordinary girl."}"""))
            MainActivity::class.java.getDeclaredMethod("hero", Media::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, fixture, true)
            MainActivity::class.java.getDeclaredMethod("mediaRow", String::class.java, List::class.java).apply { isAccessible = true }
                .invoke(activity, "Continue Watching", List(8) { fixture })
            repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS); Thread.sleep(20) }
            val view = activity.findViewById<View>(android.R.id.content)
            val width = activity.resources.displayMetrics.widthPixels
            val height = activity.resources.displayMetrics.heightPixels
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val hero = activity.findViewById<View>(R.id.featured_card)
            if (tv) {
                assertNotNull(activity.findViewById<View>(R.id.tv_navigation_rail))
                assertNull(activity.findViewById<View>(R.id.phone_bottom_navigation))
                assertTrue("TV hero must be landscape", hero.width > hero.height)
            } else {
                assertNotNull(activity.findViewById<View>(R.id.phone_bottom_navigation))
                assertNull(activity.findViewById<View>(R.id.tv_navigation_rail))
                assertTrue("Phone hero must use the inset portrait card", hero.height > hero.width)
                assertTrue("Phone hero needs side margins", hero.left >= activity.dp(20))
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/previews/${if (tv) "android-tv" else "phone"}.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test @Config(qualifiers = "w412dp-h892dp-xhdpi") fun phoneUsesMobileLayoutAndPortraitFeature() = render(false)
    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi") fun televisionUsesRailAndLandscapeFeature() = render(true)
}
