package com.cloudide.android.ui.navigation

import java.net.URLEncoder

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

object Routes {
    const val LOGIN = "login"
    const val PROJECTS = "projects"

    const val PROJECT = "project/{folderId}/{name}"
    fun project(folderId: String, name: String): String =
        "project/${enc(folderId)}/${enc(name)}"

    const val FILE = "file/{folderId}/{relPath}/{name}"
    fun file(folderId: String, relPath: String, name: String): String =
        "file/${enc(folderId)}/${enc(relPath)}/${enc(name)}"
}
