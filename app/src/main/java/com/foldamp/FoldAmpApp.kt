package com.foldamp

import android.app.Application
import com.foldamp.library.MediaLibrary
import com.foldamp.playback.Equalizer
import com.foldamp.playback.PlayerHub
import com.foldamp.skin.SkinManager

class FoldAmpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Equalizer.init(this)
        PlayerHub.init(this)
        MediaLibrary.init(this)
        SkinManager.init(this)
    }
}
