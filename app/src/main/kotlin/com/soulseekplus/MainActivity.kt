package com.soulseekplus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import desktopkit.DesktopKitRoot
import desktopkit.components.DeskIcons
import desktopkit.screens.MessageScreen
import desktopkit.settings.rememberDeskPreferences
import desktopkit.shell.DesktopShell
import desktopkit.shell.NavItem
import desktopkit.shell.NavSection
import desktopkit.shell.ShellSpec

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = rememberDeskPreferences()
            DesktopKitRoot(prefs) { widthClass ->
                DesktopShell(
                    spec = ShellSpec(
                        appTitle = "Soulseek+",
                        sections = listOf(
                            NavSection("Soulseek", listOf(NavItem("search", "Arama", DeskIcons.Home))),
                        ),
                        footer = NavItem("settings", "Ayarlar", DeskIcons.Settings),
                    ),
                    widthClass = widthClass,
                    sidebarCollapsed = prefs.sidebarCollapsed,
                    onSidebarCollapsedChange = prefs::updateSidebarCollapsed,
                ) { _, _ ->
                    MessageScreen(title = "Soulseek+", icon = DeskIcons.Home, description = "CI doğrulaması")
                }
            }
        }
    }
}
