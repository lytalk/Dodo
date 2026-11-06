package com.lytalk.dodo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lytalk.dodo.ui.AppPickerScreen
import com.lytalk.dodo.ui.DodoTheme
import com.lytalk.dodo.ui.InspectScreen
import com.lytalk.dodo.ui.PermissionScreen
import com.lytalk.dodo.ui.TaskEditRoute
import com.lytalk.dodo.ui.TaskListScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DodoTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "list") {
                    composable("list") {
                        TaskListScreen(
                            onAdd = { navController.navigate("edit?taskId=0") },
                            onEdit = { id -> navController.navigate("edit?taskId=$id") },
                            onPermissions = { navController.navigate("permissions") },
                            onInspect = { navController.navigate("inspect") },
                        )
                    }
                    composable(
                        route = "edit?taskId={taskId}",
                        arguments = listOf(
                            navArgument("taskId") {
                                type = NavType.LongType
                                defaultValue = 0L
                            },
                        ),
                    ) { entry ->
                        val taskId = entry.arguments?.getLong("taskId") ?: 0L
                        TaskEditRoute(navController = navController, taskId = taskId)
                    }
                    composable("picker") {
                        AppPickerScreen(
                            onBack = { navController.popBackStack() },
                            onPick = { packageName, label ->
                                navController.previousBackStackEntry?.savedStateHandle?.set("pickedPackage", packageName)
                                navController.previousBackStackEntry?.savedStateHandle?.set("pickedLabel", label)
                                navController.previousBackStackEntry?.savedStateHandle?.set("pickToken", System.nanoTime())
                                navController.popBackStack()
                            },
                        )
                    }
                    composable("permissions") {
                        PermissionScreen(onBack = { navController.popBackStack() })
                    }
                    composable("inspect") {
                        InspectScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
