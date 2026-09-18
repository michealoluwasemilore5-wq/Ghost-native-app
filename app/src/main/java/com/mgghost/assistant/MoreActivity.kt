package com.mgghost.assistant

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class MoreActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_more)
        findViewById<View>(R.id.more_back).setOnClickListener { finish() }
        findViewById<View>(R.id.more_settings).setOnClickListener { startActivity(Intent(this, MainActivity::class.java)); finish() }
        findViewById<View>(R.id.more_youtube).setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com"))) }
        findViewById<View>(R.id.more_chrome).setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com"))) }
        findViewById<View>(R.id.more_maps).setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=my%20location"))) }
    }
}
