package com.marklim.billsngo.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.marklim.billsngo.R

/**
 * @author Mark Lim
 * Root entry point for the app
 * Acts strictly as the root navigator / host
 * Avoid adding feature UI or business logic here
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_main)

        // Clean up any temporary model files to reclaim device storage
        filesDir.listFiles()?.filter { it.name.endsWith(".task") || it.name.endsWith(".bin") }?.forEach { it.delete() }
    }
}
