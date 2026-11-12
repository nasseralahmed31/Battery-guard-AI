package com.nasser.batteryguardai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.nasser.batteryguardai.ui.AppRoot
import com.nasser.batteryguardai.ui.VM

class MainActivity : ComponentActivity() {
    private val vm: VM by viewModels()
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AppRoot(vm) }
    }

    override fun onStart() { super.onStart(); vm.startLive() }
    override fun onStop() { super.onStop(); vm.stopLive() }
}
