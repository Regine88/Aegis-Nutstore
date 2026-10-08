package com.beemdevelopment.aegis.backup;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.beemdevelopment.aegis.AegisTest;
import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.ui.NutstoreBackupsActivity;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import dagger.hilt.android.testing.HiltAndroidTest;

/**
 * Device-side smoke test for the Nutstore settings screen: the layout must
 * inflate, the form must be usable and saving must go through the real
 * Keystore-backed credential store.
 */
@RunWith(AndroidJUnit4.class)
@HiltAndroidTest
public class NutstoreBackupsActivityTest extends AegisTest {
    @Rule
    public final ActivityScenarioRule<NutstoreBackupsActivity> activityRule =
            new ActivityScenarioRule<>(NutstoreBackupsActivity.class);

    @Before
    public void clearNutstoreState() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        new NutstoreBackupStore(context).clearAll();
        new NutstoreCredentialStore(context).clearAll();
    }

    @Test
    public void testSaveCredentialsFromUi() throws Exception {
        onView(withId(R.id.nutstore_account))
                .perform(replaceText("user@example.com"), closeSoftKeyboard());
        onView(withId(R.id.nutstore_app_password))
                .perform(replaceText("app-password"), closeSoftKeyboard());
        onView(withId(R.id.nutstore_root_path))
                .perform(replaceText("Aegis-UiTest"), closeSoftKeyboard());
        onView(withId(R.id.nutstore_save)).perform(click());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        NutstoreCredentialStore store = new NutstoreCredentialStore(context);
        NutstoreCredentialStore.Config config = store.loadConfig();
        assertEquals("user@example.com", config.getAccount());
        assertEquals("Aegis-UiTest", config.getRootPath());
        assertTrue(config.isPasswordStored());
        assertArrayEquals("app-password".toCharArray(), store.readPassword());

        store.clearAll();
    }
}
