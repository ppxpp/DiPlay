package com.shilapi.xcertplay

import android.content.res.Configuration
import android.view.View
import androidx.core.os.ConfigurationCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], qualifiers = "en-sw400dp-w400dp-h800dp-mdpi", manifest = Config.NONE)
class Android6CompatibilityTest {
    @Test fun languageAndScalingPreserveArabicOnAndroid6() {
        val app = RuntimeEnvironment.getApplication()
        AppLocale.save(app, AppLocale.ARABIC)
        val wrapped = AppLocale.wrap(app)
        val scaled = InterfaceSize.override(Configuration(wrapped.resources.configuration), 150)!!
        val override = InterfaceSize.contextOverride(scaled)
        assertEquals("ar", ConfigurationCompat.getLocales(override)[0]!!.language)
        assertEquals(View.LAYOUT_DIRECTION_RTL, override.layoutDirection)
        AppLocale.enforce(wrapped)
        assertEquals("ar", ConfigurationCompat.getLocales(wrapped.resources.configuration)[0]!!.language)
    }

    @Test fun activityAndEverySettingsCategoryOpenOnAndroid6() {
        val app = RuntimeEnvironment.getApplication()
        AppLocale.save(app, AppLocale.SIMPLIFIED_CHINESE)
        InterfaceSize.save(app, 150)
        CarPlayBackgroundSession.clear()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        try {
            controller.setup()
            val activity = controller.get()
            ReflectionHelpers.setField(activity, "page", "settings")
            for (category in SettingsCategory.entries) {
                ReflectionHelpers.setField(activity, "settingsCategory", category)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            }
            assertEquals("zh", ConfigurationCompat.getLocales(activity.resources.configuration)[0]!!.language)
        } finally {
            controller.pause().stop().destroy()
            InterfaceSize.save(app, InterfaceSize.AUTO)
            AppLocale.save(app, AppLocale.SYSTEM)
        }
    }
}
