package com.example.soundlens.ui.graph

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.example.soundlens.R
import com.example.soundlens.databinding.ActivityGraphBinding

class GraphActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGraphBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGraphBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra("plot_url")
        if (!url.isNullOrBlank()) {
            binding.imgGraph.load(url)
        } else {
            binding.imgGraph.setImageResource(R.drawable.placeholder_plot)
        }
    }
}
