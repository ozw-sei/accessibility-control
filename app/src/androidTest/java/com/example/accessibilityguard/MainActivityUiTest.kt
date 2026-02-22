package com.example.accessibilityguard

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI のテスト。
 * ステータス表示と設定画面のインタラクション確認。
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // テスト前にプリファレンスをクリア
        context.getSharedPreferences(ConditionChecker.PREF_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun statusCard_showsCurrentState() {
        composeRule.setContent {
            GuardApp()
        }

        // ステータスカードが表示される
        composeRule.onNodeWithText("現在の状態").assertIsDisplayed()
    }

    @Test
    fun settingsCard_isDisplayed() {
        composeRule.setContent {
            GuardApp()
        }

        composeRule.onNodeWithText("設定").assertIsDisplayed()
    }

    @Test
    fun setupCard_isDisplayed() {
        composeRule.setContent {
            GuardApp()
        }

        composeRule.onNodeWithText("セットアップ").assertIsDisplayed()
    }

    @Test
    fun statusCard_showsDeviceOwnerStatus() {
        composeRule.setContent {
            GuardApp()
        }

        // Device Owner の状態表示がある
        composeRule.onNodeWithText("Device Owner", substring = true).assertIsDisplayed()
    }

    @Test
    fun statusCard_showsAccessibilityServiceStatus() {
        composeRule.setContent {
            GuardApp()
        }

        composeRule.onNodeWithText("AccessibilityService", substring = true).assertIsDisplayed()
    }

    @Test
    fun statusCard_showsChargingStatus() {
        composeRule.setContent {
            GuardApp()
        }

        composeRule.onNodeWithText("充電中", substring = true).assertIsDisplayed()
    }

    @Test
    fun settingsCard_showsGuardToggle() {
        composeRule.setContent {
            GuardApp()
        }

        composeRule.onNodeWithText("ガード有効").assertIsDisplayed()
    }

    @Test
    fun settingsCard_showsChargingToggle() {
        composeRule.setContent {
            GuardApp()
        }

        composeRule.onNodeWithText("充電中のみ許可").assertIsDisplayed()
    }

    @Test
    fun settingsCard_showsSaveButton() {
        composeRule.setContent {
            GuardApp()
        }

        composeRule.onNodeWithText("保存").assertIsDisplayed()
    }
}
