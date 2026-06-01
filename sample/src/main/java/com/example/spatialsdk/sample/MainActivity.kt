package com.example.spatialsdk.sample

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.spatialsdk.sample.databinding.ActivityMainBinding
import com.nimpu.spatial.sdk.GeospatialGuidanceMode
import com.nimpu.spatial.sdk.NimpuSpatialConfig
import com.nimpu.spatial.sdk.NimpuSpatialSdk

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initializeSdk()

        binding.btnCreatePin.setOnClickListener {
            startActivity(Intent(this, ArActivity::class.java).apply {
                putExtra(ArActivity.EXTRA_MODE, ArActivity.MODE_CREATE_PIN)
            })
        }

        binding.btnResolvePin.setOnClickListener {
            startActivity(Intent(this, ArActivity::class.java).apply {
                putExtra(ArActivity.EXTRA_MODE, ArActivity.MODE_RESOLVE_PIN)
            })
        }

        binding.btnViewSavedPins.setOnClickListener {
            startActivity(Intent(this, SavedPinsActivity::class.java))
        }
    }

    private fun initializeSdk() {
        val apiKey = BuildConfig.NIMPU_API_KEY.trim()
        NimpuSpatialSdk.initialize(
            context = this,
            config = NimpuSpatialConfig(
                apiKey = apiKey.takeIf { it.isNotBlank() },
                geospatialGuidanceMode = if (BuildConfig.NIMPU_ENABLE_GEOSPATIAL_GUIDANCE) {
                    GeospatialGuidanceMode.ENABLED
                } else {
                    GeospatialGuidanceMode.DISABLED
                }
            )
        )
        binding.tvBackendStatus.text = if (apiKey.isNotBlank()) {
            "Nimpu Spatial Cloud activation configured"
        } else {
            "Nimpu API key required"
        }
    }
}
