package com.cloudide.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cloudide.android.CloudIdeApp
import com.cloudide.android.ui.screens.file.FileScreen
import com.cloudide.android.ui.screens.login.LoginScreen
import com.cloudide.android.ui.screens.project.ProjectScreen
import com.cloudide.android.ui.screens.projects.ProjectsScreen
import com.cloudide.android.ui.screens.terminal.TerminalScreen
import java.net.URLDecoder

private fun dec(s: String?) = URLDecoder.decode(s.orEmpty(), "UTF-8")

@Composable
fun CloudIdeNavHost(app: CloudIdeApp) {
    val navController: NavHostController = rememberNavController()
    val user by app.authManager.user.collectAsState()
    val startDestination = if (user != null) Routes.PROJECTS else Routes.LOGIN

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.LOGIN) {
            LoginScreen(
                authManager = app.authManager,
                onSignedIn = {
                    navController.navigate(Routes.PROJECTS) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.PROJECTS) {
            ProjectsScreen(
                app = app,
                onProjectOpen = { project ->
                    navController.navigate(Routes.project(project.driveFolderId, project.name))
                },
                onSignOut = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.PROJECTS) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.PROJECT,
            arguments = listOf(
                navArgument("folderId") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
            )
        ) { backStackEntry ->
            ProjectScreen(
                app = app,
                projectFolderId = dec(backStackEntry.arguments?.getString("folderId")),
                projectName = dec(backStackEntry.arguments?.getString("name")),
                onBack = { navController.popBackStack() },
                onFileOpen = { folderId, relPath, fileName ->
                    navController.navigate(Routes.file(folderId, relPath, fileName))
                },
                onTerminal = { folderId ->
                    navController.navigate(Routes.terminal(folderId))
                },
            )
        }
        composable(
            route = Routes.FILE,
            arguments = listOf(
                navArgument("folderId") { type = NavType.StringType },
                navArgument("relPath") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
            )
        ) { backStackEntry ->
            FileScreen(
                app = app,
                projectFolderId = dec(backStackEntry.arguments?.getString("folderId")),
                relativePath = dec(backStackEntry.arguments?.getString("relPath")),
                fileName = dec(backStackEntry.arguments?.getString("name")),
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.TERMINAL,
            arguments = listOf(
                navArgument("folderId") { type = NavType.StringType },
            )
        ) { backStackEntry ->
            TerminalScreen(
                app = app,
                projectFolderId = dec(backStackEntry.arguments?.getString("folderId")),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TERMINAL_GLOBAL) {
            TerminalScreen(
                app = app,
                projectFolderId = null,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
