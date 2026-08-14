package com.focuslock.app

import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.containsString
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingInstrumentedTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun onboardingAcceptsMatchingPasswordAndShowsDashboard() {
        onView(withId(R.id.setup_password_edit_text))
            .perform(replaceText(TEST_PASSWORD))
        onView(withId(R.id.setup_confirm_password_edit_text))
            .perform(replaceText(TEST_PASSWORD))
        closeSoftKeyboard()

        onView(withId(R.id.setup_save_password_button))
            .check(matches(isEnabled()))
            .perform(click())

        onView(withId(R.id.tv_service_status)).check(matches(isDisplayed()))
        onView(withText(containsString("Instagram"))).check(matches(isDisplayed()))
        onView(withId(R.id.btn_pause_focus)).check(matches(isDisplayed()))
    }

    private companion object {
        const val TEST_PASSWORD = "FocusTest9"
    }
}
